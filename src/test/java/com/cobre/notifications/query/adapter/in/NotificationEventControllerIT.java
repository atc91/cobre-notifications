package com.cobre.notifications.query.adapter.in;

import com.cobre.notifications.TestcontainersConfiguration;
import com.cobre.notifications.delivery.domain.model.DeliveryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test of the three self-service endpoints (P-09/P-10/P-11) over a real PostgreSQL
 * (Testcontainers) through {@link WebTestClient}. Seeders and the delivery scheduler are disabled so
 * each test controls its own rows. Pins the endpoint contract end to end: client scoping (OWASP A01),
 * filtering + pagination, the detail+attempts view, replay's state machine, and the 400/401/404/409
 * mappings.
 */
@SpringBootTest(properties = {
        "notifications.seed.enabled=false",
        "notifications.subscription-seed.enabled=false",
        "notifications.delivery.scheduler-enabled=false"
})
@Import(TestcontainersConfiguration.class)
class NotificationEventControllerIT {

    private static final Instant T0 = Instant.parse("2026-07-10T12:00:00Z");
    private static final String CLIENT_A = "CLIENT-A";
    private static final String CLIENT_B = "CLIENT-B";

    @Autowired
    ApplicationContext context;

    @Autowired
    DatabaseClient db;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        // Boot 4 no longer auto-registers a WebTestClient bean; bind one to the full WebFlux context
        // (controllers, GlobalExceptionHandler, and the snake_case-configured codecs).
        client = WebTestClient.bindToApplicationContext(context).build();
        db.sql("DELETE FROM delivery_attempts").fetch().rowsUpdated()
                .then(db.sql("DELETE FROM notifications").fetch().rowsUpdated())
                .block();
    }

    // ---- GET /notification_events (P-09) ---------------------------------------------------------

    @Test
    void listIsScopedToTheCallingClient() {
        insert("EVT-A1", CLIENT_A, DeliveryStatus.PENDING, T0);
        insert("EVT-A2", CLIENT_A, DeliveryStatus.DELIVERED, T0.plusSeconds(60));
        insert("EVT-B1", CLIENT_B, DeliveryStatus.PENDING, T0);

        client.get().uri("/notification_events").header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total_elements").isEqualTo(2)
                .jsonPath("$.content.length()").isEqualTo(2)
                .jsonPath("$.content[?(@.id == 'EVT-B1')]").doesNotExist();
    }

    @Test
    void listOrdersByCreatedAtDescending() {
        insert("EVT-OLD", CLIENT_A, DeliveryStatus.PENDING, T0);
        insert("EVT-NEW", CLIENT_A, DeliveryStatus.PENDING, T0.plusSeconds(3600));

        client.get().uri("/notification_events").header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content[0].id").isEqualTo("EVT-NEW")
                .jsonPath("$.content[1].id").isEqualTo("EVT-OLD");
    }

    @Test
    void listFiltersByDeliveryStatus() {
        insert("EVT-FAILED", CLIENT_A, DeliveryStatus.FAILED, T0);
        insert("EVT-PENDING", CLIENT_A, DeliveryStatus.PENDING, T0);

        client.get().uri("/notification_events?delivery_status=FAILED").header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total_elements").isEqualTo(1)
                .jsonPath("$.content[0].id").isEqualTo("EVT-FAILED")
                .jsonPath("$.content[0].delivery_status").isEqualTo("FAILED");
    }

    @Test
    void listFiltersByCreatedRange() {
        insert("EVT-EARLY", CLIENT_A, DeliveryStatus.PENDING, T0);
        insert("EVT-LATE", CLIENT_A, DeliveryStatus.PENDING, T0.plusSeconds(3600));

        client.get().uri(uri -> uri.path("/notification_events")
                        .queryParam("created_from", T0.plusSeconds(1800).toString()).build())
                .header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total_elements").isEqualTo(1)
                .jsonPath("$.content[0].id").isEqualTo("EVT-LATE");
    }

    @Test
    void listPaginatesWithTotalCount() {
        insert("EVT-1", CLIENT_A, DeliveryStatus.PENDING, T0);
        insert("EVT-2", CLIENT_A, DeliveryStatus.PENDING, T0.plusSeconds(60));
        insert("EVT-3", CLIENT_A, DeliveryStatus.PENDING, T0.plusSeconds(120));

        client.get().uri("/notification_events?page=0&size=2").header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.size").isEqualTo(2)
                .jsonPath("$.total_elements").isEqualTo(3)
                .jsonPath("$.content.length()").isEqualTo(2);

        client.get().uri("/notification_events?page=1&size=2").header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total_elements").isEqualTo(3)
                .jsonPath("$.content.length()").isEqualTo(1);
    }

    @Test
    void listRejectsUnknownDeliveryStatusWith400() {
        client.get().uri("/notification_events?delivery_status=BOGUS").header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void listRejectsUnparseableDateWith400() {
        client.get().uri("/notification_events?created_from=not-a-date").header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void listWithoutClientHeaderIs401() {
        client.get().uri("/notification_events")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---- GET /notification_events/{id} (P-10) ----------------------------------------------------

    @Test
    void detailReturnsNotificationWithAttemptHistory() {
        insert("EVT-DETAIL", CLIENT_A, DeliveryStatus.FAILED, T0);
        insertAttempt("EVT-DETAIL", 1, 500, "HTTP 500", 12L, T0.plusSeconds(1));
        insertAttempt("EVT-DETAIL", 2, 503, "HTTP 503", 15L, T0.plusSeconds(2));

        client.get().uri("/notification_events/{id}", "EVT-DETAIL").header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("EVT-DETAIL")
                .jsonPath("$.client_id").isEqualTo(CLIENT_A)
                .jsonPath("$.delivery_status").isEqualTo("FAILED")
                .jsonPath("$.delivery_attempts.length()").isEqualTo(2)
                .jsonPath("$.delivery_attempts[0].attempt_no").isEqualTo(1)
                .jsonPath("$.delivery_attempts[0].http_status").isEqualTo(500)
                .jsonPath("$.delivery_attempts[1].attempt_no").isEqualTo(2);
    }

    @Test
    void detailForAnotherClientsNotificationIs404() {
        insert("EVT-OWNED-BY-B", CLIENT_B, DeliveryStatus.PENDING, T0);

        client.get().uri("/notification_events/{id}", "EVT-OWNED-BY-B").header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void detailForMissingNotificationIs404() {
        client.get().uri("/notification_events/{id}", "DOES-NOT-EXIST").header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void detailWithoutClientHeaderIs401() {
        insert("EVT-X", CLIENT_A, DeliveryStatus.PENDING, T0);

        client.get().uri("/notification_events/{id}", "EVT-X")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---- POST /notification_events/{id}/replay (P-11) --------------------------------------------

    @Test
    void replayOfFailedNotificationResetsItToPendingDueNow() {
        insert("EVT-REPLAY", CLIENT_A, DeliveryStatus.FAILED, T0);

        client.post().uri("/notification_events/{id}/replay", "EVT-REPLAY").header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isAccepted();

        assertThat(countWhere("delivery_status = 'PENDING'", "EVT-REPLAY")).isEqualTo(1);
        assertThat(countWhere("next_retry_at IS NOT NULL", "EVT-REPLAY")).isEqualTo(1);
        assertThat(countWhere("claimed_at IS NULL", "EVT-REPLAY")).isEqualTo(1);
        assertThat(countWhere("last_error IS NULL", "EVT-REPLAY")).isEqualTo(1);
    }

    @Test
    void replayOfNonFailedNotificationIs409AndLeavesItUnchanged() {
        insert("EVT-DELIVERED", CLIENT_A, DeliveryStatus.DELIVERED, T0);

        client.post().uri("/notification_events/{id}/replay", "EVT-DELIVERED").header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isEqualTo(409);

        assertThat(countWhere("delivery_status = 'DELIVERED'", "EVT-DELIVERED")).isEqualTo(1);
    }

    @Test
    void replayOfAnotherClientsNotificationIs404AndLeavesItUnchanged() {
        insert("EVT-B-FAILED", CLIENT_B, DeliveryStatus.FAILED, T0);

        client.post().uri("/notification_events/{id}/replay", "EVT-B-FAILED").header("X-Client-Id", CLIENT_A)
                .exchange()
                .expectStatus().isNotFound();

        assertThat(countWhere("delivery_status = 'FAILED'", "EVT-B-FAILED")).isEqualTo(1);
    }

    @Test
    void replayWithoutClientHeaderIs401() {
        insert("EVT-REPLAY-2", CLIENT_A, DeliveryStatus.FAILED, T0);

        client.post().uri("/notification_events/{id}/replay", "EVT-REPLAY-2")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---- seeding helpers -------------------------------------------------------------------------

    private void insert(String id, String client, DeliveryStatus status, Instant createdAt) {
        boolean failed = status == DeliveryStatus.FAILED;
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO notifications
                            (id, client_id, event_type, content, target_url, delivery_status, attempts,
                             last_error, created_at, updated_at)
                        VALUES
                            (:id, :client, :eventType, :content, :url, :status, :attempts,
                             :lastError, :createdAt, :createdAt)
                        """)
                .bind("id", id)
                .bind("client", client)
                .bind("eventType", "credit_card_payment")
                .bind("content", "Payment received")
                .bind("url", "https://client.example/hook")
                .bind("status", status.name())
                .bind("attempts", failed ? 5 : 0)
                .bind("createdAt", createdAt);
        // A FAILED row carries a last_error (cleared on replay); other seeded states have none.
        spec = failed ? spec.bind("lastError", "boom") : spec.bindNull("lastError", String.class);
        spec.fetch().rowsUpdated().block();
    }

    private void insertAttempt(String notificationId, int attemptNo, Integer httpStatus,
                               String error, long durationMs, Instant at) {
        db.sql("""
                        INSERT INTO delivery_attempts
                            (notification_id, attempt_no, attempted_at, http_status, error, duration_ms)
                        VALUES
                            (:nid, :no, :at, :status, :error, :dur)
                        """)
                .bind("nid", notificationId)
                .bind("no", attemptNo)
                .bind("at", at)
                .bind("status", httpStatus)
                .bind("error", error)
                .bind("dur", durationMs)
                .fetch().rowsUpdated()
                .block();
    }

    private long countWhere(String predicate, String id) {
        Long count = db.sql("SELECT count(*) FROM notifications WHERE id = :id AND " + predicate)
                .bind("id", id)
                .map(row -> row.get(0, Long.class))
                .one()
                .block();
        return count == null ? 0 : count;
    }
}
