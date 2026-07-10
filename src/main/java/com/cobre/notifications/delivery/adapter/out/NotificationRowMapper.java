package com.cobre.notifications.delivery.adapter.out;

import com.cobre.notifications.delivery.domain.model.DeliveryStatus;
import com.cobre.notifications.delivery.domain.model.Notification;
import io.r2dbc.spi.Readable;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Shared mapping from a {@code notifications} row to the {@link Notification} aggregate, used by both
 * the write-path adapter ({@link R2dbcNotificationRepository}) and the read/replay adapter
 * ({@link R2dbcNotificationReadAdapter}) so the column-to-field contract lives in one place.
 */
final class NotificationRowMapper {

    private NotificationRowMapper() {
    }

    static Notification mapRow(Readable row) {
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

    static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
