package com.cobre.notifications.delivery.adapter.out;

import com.cobre.notifications.common.error.ConflictException;
import com.cobre.notifications.common.error.NotFoundException;
import com.cobre.notifications.delivery.domain.model.DeliveryAttempt;
import com.cobre.notifications.delivery.domain.model.Notification;
import com.cobre.notifications.delivery.domain.model.NotificationFilter;
import com.cobre.notifications.delivery.domain.model.NotificationPage;
import com.cobre.notifications.delivery.domain.port.out.ClockPort;
import com.cobre.notifications.delivery.domain.port.out.NotificationReadPort;
import com.cobre.notifications.delivery.domain.port.out.NotificationReplayPort;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * R2DBC adapter serving the {@code query} context's read + replay needs over the delivery-owned
 * {@code notifications} / {@code delivery_attempts} tables (P-09/P-10/P-11). Kept separate from the
 * write-path {@link R2dbcNotificationRepository} so the ingest/deliver hot path and the self-service
 * query surface evolve independently, though both map rows through {@link NotificationRowMapper}.
 *
 * <p>Every query is filtered by {@code client_id} in SQL — the OWASP A01 scoping guarantee lives here,
 * not in the controller. All values are bound as parameters (never concatenated), so the dynamic
 * {@code WHERE} clause is not an injection vector (A03).
 */
@Repository
public class R2dbcNotificationReadAdapter implements NotificationReadPort, NotificationReplayPort {

    // Replay reuses the delivery state columns; no delivery_attempts row is written on a replay.
    private static final String UPDATE_STATE = """
            UPDATE notifications
               SET delivery_status = :deliveryStatus, attempts = :attempts, next_retry_at = :nextRetryAt,
                   claimed_at = :claimedAt, last_error = :lastError, delivered_at = :deliveredAt,
                   updated_at = :updatedAt
             WHERE id = :id
            """;

    private final DatabaseClient db;
    private final TransactionalOperator tx;
    private final ClockPort clock;

    public R2dbcNotificationReadAdapter(DatabaseClient db, ReactiveTransactionManager txManager, ClockPort clock) {
        this.db = db;
        this.tx = TransactionalOperator.create(txManager);
        this.clock = clock;
    }

    @Override
    public Mono<NotificationPage> findPage(String clientId, NotificationFilter filter, int page, int size) {
        String where = buildWhere(filter);
        String listSql = "SELECT * FROM notifications " + where
                + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset";
        String countSql = "SELECT count(*) FROM notifications " + where;

        Mono<List<Notification>> content = bindFilter(db.sql(listSql), clientId, filter)
                .bind("limit", size)
                .bind("offset", (long) page * size)
                .map(NotificationRowMapper::mapRow)
                .all()
                .collectList();
        Mono<Long> total = bindFilter(db.sql(countSql), clientId, filter)
                .map(row -> row.get(0, Long.class))
                .one();
        return Mono.zip(content, total, NotificationPage::new);
    }

    @Override
    public Mono<Notification> findByIdForClient(String id, String clientId) {
        return db.sql("SELECT * FROM notifications WHERE id = :id AND client_id = :clientId")
                .bind("id", id)
                .bind("clientId", clientId)
                .map(NotificationRowMapper::mapRow)
                .one();
    }

    @Override
    public Flux<DeliveryAttempt> findAttempts(String notificationId) {
        return db.sql("SELECT * FROM delivery_attempts WHERE notification_id = :id ORDER BY attempt_no")
                .bind("id", notificationId)
                .map(R2dbcNotificationReadAdapter::mapAttempt)
                .all();
    }

    @Override
    public Mono<Void> replay(String id, String clientId) {
        Instant now = clock.now();
        // Lock the owned row, guard its state, and write the FAILED -> PENDING transition in one
        // transaction so a concurrent replay/claim cannot interleave. Ownership is checked before the
        // status guard, so a not-owned id yields 404 (never a 409 that would leak existence).
        return db.sql("SELECT * FROM notifications WHERE id = :id AND client_id = :clientId FOR UPDATE")
                .bind("id", id)
                .bind("clientId", clientId)
                .map(NotificationRowMapper::mapRow)
                .one()
                .switchIfEmpty(Mono.error(() -> new NotFoundException("notification not found: " + id)))
                .flatMap(notification -> {
                    if (!notification.deliveryStatus().isReplayable()) {
                        return Mono.error(new ConflictException(
                                "notification " + id + " is not FAILED (status "
                                        + notification.deliveryStatus() + ")"));
                    }
                    notification.replay(now);
                    return update(notification);
                })
                .as(tx::transactional)
                .then();
    }

    private Mono<Long> update(Notification n) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(UPDATE_STATE)
                .bind("id", n.id())
                .bind("deliveryStatus", n.deliveryStatus().name())
                .bind("attempts", n.attempts())
                .bind("updatedAt", n.updatedAt());
        spec = bindNullable(spec, "nextRetryAt", n.nextRetryAt(), Instant.class);
        spec = bindNullable(spec, "claimedAt", n.claimedAt(), Instant.class);
        spec = bindNullable(spec, "lastError", n.lastError(), String.class);
        spec = bindNullable(spec, "deliveredAt", n.deliveredAt(), Instant.class);
        return spec.fetch().rowsUpdated();
    }

    /** {@code WHERE client_id = :clientId} plus one clause per non-null filter field. */
    private static String buildWhere(NotificationFilter filter) {
        StringBuilder sb = new StringBuilder("WHERE client_id = :clientId");
        if (filter.createdFrom() != null) {
            sb.append(" AND created_at >= :createdFrom");
        }
        if (filter.createdTo() != null) {
            sb.append(" AND created_at <= :createdTo");
        }
        if (filter.status() != null) {
            sb.append(" AND delivery_status = :status");
        }
        return sb.toString();
    }

    private static DatabaseClient.GenericExecuteSpec bindFilter(
            DatabaseClient.GenericExecuteSpec spec, String clientId, NotificationFilter filter) {
        spec = spec.bind("clientId", clientId);
        if (filter.createdFrom() != null) {
            spec = spec.bind("createdFrom", filter.createdFrom());
        }
        if (filter.createdTo() != null) {
            spec = spec.bind("createdTo", filter.createdTo());
        }
        if (filter.status() != null) {
            spec = spec.bind("status", filter.status().name());
        }
        return spec;
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, Object value, Class<?> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private static DeliveryAttempt mapAttempt(Readable row) {
        return new DeliveryAttempt(
                row.get("id", UUID.class),
                row.get("notification_id", String.class),
                row.get("attempt_no", Integer.class),
                NotificationRowMapper.toInstant(row.get("attempted_at", OffsetDateTime.class)),
                row.get("http_status", Integer.class),
                row.get("error", String.class),
                row.get("duration_ms", Long.class));
    }
}
