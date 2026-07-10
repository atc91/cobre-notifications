package com.cobre.notifications.query.application;

import com.cobre.notifications.TestcontainersConfiguration;
import com.cobre.notifications.delivery.domain.model.DeliveryStatus;
import com.cobre.notifications.delivery.domain.port.in.DeliverNotificationUseCase;
import com.cobre.notifications.delivery.domain.port.out.NotificationStorePort;
import com.cobre.notifications.query.domain.port.in.ReplayNotificationUseCase;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end walkthrough closing the delivery loop (P-11): a dead-lettered ({@code FAILED})
 * notification is replayed through {@link ReplayNotificationUseCase}, and the <em>existing</em>
 * delivery engine ({@link DeliverNotificationUseCase}, P-07/P-08) then drives it to {@code DELIVERED}
 * against a real webhook stub — proving replay re-enters the retry pipeline with no separate
 * re-delivery path. Real PostgreSQL (Testcontainers) + a real HTTP call to {@link MockWebServer}.
 */
@SpringBootTest(properties = {
        "notifications.seed.enabled=false",
        "notifications.subscription-seed.enabled=false",
        "notifications.webhook.block-private-networks=false",
        "notifications.delivery.scheduler-enabled=false"
})
@Import(TestcontainersConfiguration.class)
class ReplayDeliveryWalkthroughIT {

    private static final Instant T0 = Instant.parse("2026-07-10T12:00:00Z");
    private static final String CLIENT = "CLIENT001";

    @Autowired
    ReplayNotificationUseCase replay;

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

    @Test
    void replayReDeliversAFailedNotificationToSuccess() {
        seedFailed("EVT-DEAD-LETTER", server.url("/hook").toString());
        server.enqueue(new MockResponse().setResponseCode(200));

        // replay (FAILED -> PENDING due now) then let the delivery engine claim and deliver it.
        StepVerifier.create(
                        replay.replay(CLIENT, "EVT-DEAD-LETTER")
                                .then(deliver.deliverDue())
                                .then(store.findById("EVT-DEAD-LETTER")))
                .assertNext(n -> {
                    assertThat(n.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
                    assertThat(n.deliveredAt()).isNotNull();
                })
                .verifyComplete();

        // The re-delivery recorded a fresh append-only attempt row.
        StepVerifier.create(db.sql("SELECT count(*) FROM delivery_attempts WHERE notification_id = :id")
                        .bind("id", "EVT-DEAD-LETTER").map(row -> row.get(0, Long.class)).one())
                .assertNext(count -> assertThat(count).isGreaterThanOrEqualTo(1))
                .verifyComplete();
    }

    private void seedFailed(String id, String targetUrl) {
        db.sql("""
                        INSERT INTO notifications
                            (id, client_id, event_type, content, target_url, delivery_status, attempts,
                             last_error, created_at, updated_at)
                        VALUES
                            (:id, :client, :eventType, :content, :url, 'FAILED', 5, 'gave up', :now, :now)
                        """)
                .bind("id", id)
                .bind("client", CLIENT)
                .bind("eventType", "credit_card_payment")
                .bind("content", "Payment received")
                .bind("url", targetUrl)
                .bind("now", T0)
                .fetch().rowsUpdated()
                .block();
    }
}
