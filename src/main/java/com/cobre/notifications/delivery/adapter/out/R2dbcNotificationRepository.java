package com.cobre.notifications.delivery.adapter.out;

import com.cobre.notifications.delivery.domain.model.DeliveryAttempt;
import com.cobre.notifications.delivery.domain.model.DeliveryStatus;
import com.cobre.notifications.delivery.domain.model.Notification;
import com.cobre.notifications.delivery.domain.port.out.NotificationStorePort;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * R2DBC adapter implementing {@link NotificationStorePort} over the {@code notifications} and
 * {@code delivery_attempts} tables (P-02). Built on {@link DatabaseClient} with explicit SQL because
 * the persistence semantics this service needs — {@code ON CONFLICT DO NOTHING} on ingest,
 * {@code FOR UPDATE SKIP LOCKED} to claim due work — are not expressible through a derived Spring Data
 * method.
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

    // Atomic claim: select the due rows with SKIP LOCKED and flip them to DELIVERING in one statement,
    // so concurrent workers / overlapping polls partition the work and never double-deliver. PENDING
    // rows have a null next_retry_at (due immediately); RETRYING rows are due once next_retry_at passes.
    private static final String CLAIM_DUE = """
            UPDATE notifications
               SET delivery_status = 'DELIVERING', claimed_at = :now,
                   attempts = attempts + 1, updated_at = :now
             WHERE id IN (
                 SELECT id FROM notifications
                  WHERE delivery_status IN ('PENDING', 'RETRYING')
                    AND (next_retry_at IS NULL OR next_retry_at <= :now)
                  ORDER BY next_retry_at NULLS FIRST
                  FOR UPDATE SKIP LOCKED
                  LIMIT :limit)
            RETURNING *
            """;

    private static final String UPDATE_STATE = """
            UPDATE notifications
               SET delivery_status = :deliveryStatus, attempts = :attempts, next_retry_at = :nextRetryAt,
                   claimed_at = :claimedAt, last_error = :lastError, delivered_at = :deliveredAt,
                   updated_at = :updatedAt
             WHERE id = :id
            """;

    private static final String INSERT_ATTEMPT = """
            INSERT INTO delivery_attempts
                (notification_id, attempt_no, attempted_at, http_status, error, duration_ms)
            VALUES
                (:notificationId, :attemptNo, :attemptedAt, :httpStatus, :error, :durationMs)
            """;

    private final DatabaseClient db;
    private final TransactionalOperator tx;

    public R2dbcNotificationRepository(DatabaseClient db, ReactiveTransactionManager txManager) {
        this.db = db;
        this.tx = TransactionalOperator.create(txManager);
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
        return db.sql(CLAIM_DUE)
                .bind("now", now)
                .bind("limit", limit)
                .map(R2dbcNotificationRepository::mapRow)
                .all();
    }

    @Override
    public Mono<Void> recordOutcome(Notification n, DeliveryAttempt attempt) {
        DatabaseClient.GenericExecuteSpec updateSpec = db.sql(UPDATE_STATE)
                .bind("id", n.id())
                .bind("deliveryStatus", n.deliveryStatus().name())
                .bind("attempts", n.attempts())
                .bind("updatedAt", n.updatedAt());
        updateSpec = bindNullable(updateSpec, "nextRetryAt", n.nextRetryAt(), Instant.class);
        updateSpec = bindNullable(updateSpec, "claimedAt", n.claimedAt(), Instant.class);
        updateSpec = bindNullable(updateSpec, "lastError", n.lastError(), String.class);
        updateSpec = bindNullable(updateSpec, "deliveredAt", n.deliveredAt(), Instant.class);

        DatabaseClient.GenericExecuteSpec attemptSpec = db.sql(INSERT_ATTEMPT)
                .bind("notificationId", attempt.notificationId())
                .bind("attemptNo", attempt.attemptNo())
                .bind("attemptedAt", attempt.attemptedAt());
        attemptSpec = bindNullable(attemptSpec, "httpStatus", attempt.httpStatus(), Integer.class);
        attemptSpec = bindNullable(attemptSpec, "error", attempt.error(), String.class);
        attemptSpec = bindNullable(attemptSpec, "durationMs", attempt.durationMs(), Long.class);

        // One transaction: the advanced notification state and its audit row commit together (step 3).
        return updateSpec.fetch().rowsUpdated()
                .then(attemptSpec.fetch().rowsUpdated())
                .as(tx::transactional)
                .then();
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
