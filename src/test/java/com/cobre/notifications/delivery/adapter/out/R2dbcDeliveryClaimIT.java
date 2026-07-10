package com.cobre.notifications.delivery.adapter.out;

import com.cobre.notifications.TestcontainersConfiguration;
import com.cobre.notifications.delivery.domain.model.DeliveryAttempt;
import com.cobre.notifications.delivery.domain.model.DeliveryStatus;
import com.cobre.notifications.delivery.domain.model.Notification;
import com.cobre.notifications.delivery.domain.port.out.NotificationStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the claim + record half of the store adapter (P-07) against a real PostgreSQL
 * (Testcontainers). Pins the {@code FOR UPDATE SKIP LOCKED} due-work claim (only due rows, leased to
 * DELIVERING with attempts incremented; concurrent claims never overlap) and the atomic
 * {@code recordOutcome}. Each test starts from an empty table so claims are deterministic.
 */
@SpringBootTest(properties = "notifications.test-suite=delivery-claim")
@Import(TestcontainersConfiguration.class)
class R2dbcDeliveryClaimIT {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Autowired
    NotificationStorePort store;

    @Autowired
    DatabaseClient db;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM delivery_attempts").fetch().rowsUpdated()
                .then(db.sql("DELETE FROM notifications").fetch().rowsUpdated())
                .block();
    }

    private Notification insert(String id, DeliveryStatus status, Instant nextRetryAt) {
        Notification n = new Notification(id, "CLIENT-C", "credit_card_payment", "content",
                "https://client.example/webhook", status, 0, nextRetryAt, null, null, NOW, null, NOW);
        store.save(n).as(StepVerifier::create).expectNextCount(1).verifyComplete();
        return n;
    }

    @Test
    void claimsOnlyDueRowsAndLeasesThem() {
        insert("DUE-PENDING", DeliveryStatus.PENDING, null);                   // due (no next_retry_at)
        insert("DUE-RETRY", DeliveryStatus.RETRYING, NOW.minusSeconds(10));    // due (past next_retry_at)
        insert("NOT-DUE", DeliveryStatus.RETRYING, NOW.plusSeconds(3600));     // future — not due
        insert("TERMINAL", DeliveryStatus.DELIVERED, null);                    // terminal — never claimed

        StepVerifier.create(store.claimDue(NOW, 10).collectList())
                .assertNext(claimed -> {
                    assertThat(claimed).extracting(Notification::id)
                            .containsExactlyInAnyOrder("DUE-PENDING", "DUE-RETRY");
                    assertThat(claimed).allSatisfy(n -> {
                        assertThat(n.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERING);
                        assertThat(n.attempts()).isEqualTo(1);
                        assertThat(n.claimedAt()).isEqualTo(NOW);
                    });
                })
                .verifyComplete();

        // Non-due and terminal rows are untouched.
        assertStatus("NOT-DUE", DeliveryStatus.RETRYING);
        assertStatus("TERMINAL", DeliveryStatus.DELIVERED);
    }

    @Test
    void concurrentClaimsNeverOverlap() {
        for (int i = 0; i < 6; i++) {
            insert("ROW-" + i, DeliveryStatus.PENDING, null);
        }

        Flux<String> claimA = store.claimDue(NOW, 6).subscribeOn(Schedulers.parallel()).map(Notification::id);
        Flux<String> claimB = store.claimDue(NOW, 6).subscribeOn(Schedulers.parallel()).map(Notification::id);

        StepVerifier.create(Flux.merge(claimA, claimB).collectList())
                .assertNext(ids -> {
                    // Every due row is claimed exactly once across the two concurrent claims — no overlap.
                    assertThat(ids).hasSize(6);
                    assertThat(Set.copyOf(ids)).hasSize(6);
                })
                .verifyComplete();
    }

    @Test
    void recordOutcomeUpdatesNotificationAndAppendsAttempt() {
        Notification n = new Notification("REC", "CLIENT-C", "credit_card_payment", "content",
                "https://client.example/webhook", DeliveryStatus.DELIVERING, 1, null, NOW, null, NOW, null, NOW);
        store.save(n).as(StepVerifier::create).expectNextCount(1).verifyComplete();

        Instant delivered = NOW.plusSeconds(1);
        n.recordSuccess(delivered); // DELIVERING -> DELIVERED
        DeliveryAttempt attempt = new DeliveryAttempt(null, "REC", 1, delivered, 200, null, 12L);

        StepVerifier.create(store.recordOutcome(n, attempt).then(store.findById("REC")))
                .assertNext(found -> {
                    assertThat(found.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
                    assertThat(found.deliveredAt()).isEqualTo(delivered);
                    assertThat(found.nextRetryAt()).isNull();
                })
                .verifyComplete();

        StepVerifier.create(db.sql("SELECT count(*) FROM delivery_attempts WHERE notification_id = 'REC'")
                        .map(row -> row.get(0, Long.class)).one())
                .assertNext(count -> assertThat(count).isEqualTo(1L))
                .verifyComplete();
    }

    private void assertStatus(String id, DeliveryStatus expected) {
        StepVerifier.create(store.findById(id))
                .assertNext(n -> assertThat(n.deliveryStatus()).isEqualTo(expected))
                .verifyComplete();
    }
}
