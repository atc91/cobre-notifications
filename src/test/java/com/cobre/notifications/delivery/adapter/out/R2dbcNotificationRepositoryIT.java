package com.cobre.notifications.delivery.adapter.out;

import com.cobre.notifications.TestcontainersConfiguration;
import com.cobre.notifications.delivery.domain.model.DeliveryStatus;
import com.cobre.notifications.delivery.domain.model.Notification;
import com.cobre.notifications.delivery.domain.port.out.NotificationStorePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the R2DBC store adapter against a real PostgreSQL (Testcontainers). Pins down the
 * ingest half of the port contract: a round-trip through {@code save}/{@code findById} and the
 * idempotent {@code ON CONFLICT DO NOTHING} behaviour that makes re-ingest a no-op.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class R2dbcNotificationRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Autowired
    NotificationStorePort store;

    @Autowired
    DatabaseClient db;

    @Test
    void saveThenFindByIdRoundTripsAPendingNotification() {
        Notification n = Notification.pending(
                "IT-EVT-1", "CLIENT-A", "credit_deposit", "Direct deposit received", null, NOW);

        StepVerifier.create(store.save(n).then(store.findById("IT-EVT-1")))
                .assertNext(found -> {
                    assertThat(found.id()).isEqualTo("IT-EVT-1");
                    assertThat(found.clientId()).isEqualTo("CLIENT-A");
                    assertThat(found.eventType()).isEqualTo("credit_deposit");
                    assertThat(found.content()).isEqualTo("Direct deposit received");
                    assertThat(found.targetUrl()).isNull();
                    assertThat(found.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
                    assertThat(found.attempts()).isZero();
                    assertThat(found.nextRetryAt()).isNull();
                    assertThat(found.claimedAt()).isNull();
                    assertThat(found.deliveredAt()).isNull();
                    assertThat(found.createdAt()).isEqualTo(NOW);
                    assertThat(found.updatedAt()).isEqualTo(NOW);
                })
                .verifyComplete();
    }

    @Test
    void saveIsIdempotentOnDuplicateId() {
        Notification original = Notification.pending(
                "IT-EVT-2", "CLIENT-A", "credit_deposit", "original content", null, NOW);
        Notification duplicate = Notification.pending(
                "IT-EVT-2", "CLIENT-A", "credit_deposit", "DIFFERENT content", null, NOW.plusSeconds(3600));

        // Re-saving the same id must neither error, overwrite, nor duplicate: ON CONFLICT DO NOTHING.
        StepVerifier.create(store.save(original).then(store.save(duplicate)).then(store.findById("IT-EVT-2")))
                .assertNext(found -> assertThat(found.content()).isEqualTo("original content"))
                .verifyComplete();

        StepVerifier.create(db.sql("SELECT count(*) FROM notifications WHERE id = 'IT-EVT-2'")
                        .map(row -> row.get(0, Long.class)).one())
                .assertNext(count -> assertThat(count).isEqualTo(1L))
                .verifyComplete();
    }
}
