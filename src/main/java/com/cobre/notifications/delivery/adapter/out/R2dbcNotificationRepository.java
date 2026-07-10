package com.cobre.notifications.delivery.adapter.out;

import com.cobre.notifications.delivery.domain.model.DeliveryAttempt;
import com.cobre.notifications.delivery.domain.model.DeliveryStatus;
import com.cobre.notifications.delivery.domain.model.Notification;
import com.cobre.notifications.delivery.domain.port.out.NotificationStorePort;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * R2DBC adapter implementing {@link NotificationStorePort} over the {@code notifications} table (P-02).
 * Built on {@link DatabaseClient} with explicit SQL because the persistence semantics this service needs
 * — {@code ON CONFLICT DO NOTHING} here, {@code FOR UPDATE SKIP LOCKED} in P-07 — are not expressible
 * through a derived Spring Data method.
 *
 * <p>Only the ingest half ({@link #save} / {@link #findById}) is implemented in P-04; {@link #claimDue}
 * and {@link #recordAttempt} arrive with the delivery scheduler (P-07/P-08).
 */
@Repository
public class R2dbcNotificationRepository implements NotificationStorePort {

    private static final String INSERT = """
            INSERT INTO notifications
                (id, client_id, event_type, content, target_url, delivery_status, attempts,
                 next_retry_at, claimed_at, last_error, created_at, delivered_at, updated_at)
            VALUES
                (:id, :clientId, :eventType, :content, :targetUrl, :deliveryStatus, :attempts,
                 :nextRetryAt, :claimedAt, :lastError, :createdAt, :deliveredAt, :updatedAt)
            ON CONFLICT (id) DO NOTHING
            """;

    private final DatabaseClient db;

    public R2dbcNotificationRepository(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Notification> save(Notification n) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(INSERT)
                .bind("id", n.id())
                .bind("clientId", n.clientId())
                .bind("eventType", n.eventType())
                .bind("content", n.content())
                .bind("deliveryStatus", n.deliveryStatus().name())
                .bind("attempts", n.attempts())
                .bind("createdAt", n.createdAt())
                .bind("updatedAt", n.updatedAt());
        spec = bindNullable(spec, "targetUrl", n.targetUrl(), String.class);
        spec = bindNullable(spec, "nextRetryAt", n.nextRetryAt(), Instant.class);
        spec = bindNullable(spec, "claimedAt", n.claimedAt(), Instant.class);
        spec = bindNullable(spec, "lastError", n.lastError(), String.class);
        spec = bindNullable(spec, "deliveredAt", n.deliveredAt(), Instant.class);
        // ON CONFLICT DO NOTHING: a duplicate id leaves the existing row untouched. We still return the
        // intended aggregate so ingest is a clean no-op regardless of whether the row was new.
        return spec.fetch().rowsUpdated().thenReturn(n);
    }

    @Override
    public Mono<Notification> findById(String id) {
        return db.sql("SELECT * FROM notifications WHERE id = :id")
                .bind("id", id)
                .map(R2dbcNotificationRepository::mapRow)
                .one();
    }

    @Override
    public Flux<Notification> claimDue(Instant now, int limit) {
        // TODO(P-07): SELECT ... FOR UPDATE SKIP LOCKED claim of due work.
        throw new UnsupportedOperationException("claimDue is implemented in P-07");
    }

    @Override
    public Mono<Void> recordAttempt(DeliveryAttempt attempt) {
        // TODO(P-07): append a delivery_attempts row and advance the notification status.
        throw new UnsupportedOperationException("recordAttempt is implemented in P-07");
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, Object value, Class<?> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private static Notification mapRow(Readable row) {
        return new Notification(
                row.get("id", String.class),
                row.get("client_id", String.class),
                row.get("event_type", String.class),
                row.get("content", String.class),
                row.get("target_url", String.class),
                DeliveryStatus.valueOf(row.get("delivery_status", String.class)),
                row.get("attempts", Integer.class),
                toInstant(row.get("next_retry_at", OffsetDateTime.class)),
                toInstant(row.get("claimed_at", OffsetDateTime.class)),
                row.get("last_error", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("delivered_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
