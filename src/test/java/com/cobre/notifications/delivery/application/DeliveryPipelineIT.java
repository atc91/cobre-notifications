package com.cobre.notifications.delivery.application;

import com.cobre.notifications.TestcontainersConfiguration;
import com.cobre.notifications.delivery.domain.model.DeliveryStatus;
import com.cobre.notifications.delivery.domain.model.Notification;
import com.cobre.notifications.delivery.domain.port.in.DeliverNotificationUseCase;
import com.cobre.notifications.delivery.domain.port.out.NotificationStorePort;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test of the delivery pipeline through the retry engine (P-07/P-08): real
 * PostgreSQL (Testcontainers), a real webhook call to an OkHttp {@link MockWebServer}, and the actual
 * use case. The {@code notifications.retry.*} properties dial the back-off to zero and cap attempts at
 * 3, so each {@code RETRYING} row is due immediately and a few successive {@code deliverDue()} calls
 * exercise the flaky-stub journey: retry → success, and retry → exhaustion (dead-letter to FAILED).
 */
@SpringBootTest(properties = {
        "notifications.webhook.block-private-networks=false",
        "notifications.delivery.scheduler-enabled=false",
        "notifications.retry.max-attempts=3",
        "notifications.retry.base-delay-ms=0",
        "notifications.retry.cap-ms=0"
})
@Import(TestcontainersConfiguration.class)
class DeliveryPipelineIT {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    @Autowired
    DeliverNotificationUseCase deliver;

    @Autowired
    NotificationStorePort store;

    @Autowired
    DatabaseClient db;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        db.sql("DELETE FROM delivery_attempts").fetch().rowsUpdated()
                .then(db.sql("DELETE FROM notifications").fetch().rowsUpdated())
                .block();
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private void seedPending(String id) {
        Notification n = Notification.pending(
                id, "CLIENT001", "credit_card_payment", "Payment received",
                server.url("/hook").toString(), NOW);
        store.save(n).as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }

    @Test
    void retriesThenSucceeds() {
        seedPending("EVT-RETRY-OK");
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(200));

        StepVerifier.create(threeRuns().then(store.findById("EVT-RETRY-OK")))
                .assertNext(n -> {
                    assertThat(n.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
                    assertThat(n.attempts()).isEqualTo(3);
                    assertThat(n.deliveredAt()).isNotNull();
                })
                .verifyComplete();

        assertAttemptCount("EVT-RETRY-OK", 3);
    }

    @Test
    void retriesThenDeadLetters() {
        seedPending("EVT-EXHAUST");
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(threeRuns().then(store.findById("EVT-EXHAUST")))
                .assertNext(n -> {
                    assertThat(n.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
                    assertThat(n.attempts()).isEqualTo(3);
                    assertThat(n.nextRetryAt()).isNull();
                })
                .verifyComplete();

        assertAttemptCount("EVT-EXHAUST", 3);
    }

    /** Three sequential delivery polls; each claims the single due row and makes one webhook call. */
    private Mono<Void> threeRuns() {
        return deliver.deliverDue().then(deliver.deliverDue()).then(deliver.deliverDue());
    }

    private void assertAttemptCount(String id, long expected) {
        StepVerifier.create(db.sql("SELECT count(*) FROM delivery_attempts WHERE notification_id = :id")
                        .bind("id", id).map(row -> row.get(0, Long.class)).one())
                .assertNext(count -> assertThat(count).isEqualTo(expected))
                .verifyComplete();
    }
}
