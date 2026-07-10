package com.cobre.notifications.query.adapter.in.dto;

import com.cobre.notifications.delivery.domain.model.Notification;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * List-item view of a notification (P-09). {@link JsonProperty} pins the snake_case wire names of the
 * API contract explicitly, independent of any global naming strategy.
 */
public record NotificationSummary(
        String id,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("delivery_status") String deliveryStatus,
        int attempts,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("next_retry_at") Instant nextRetryAt) {

    public static NotificationSummary from(Notification n) {
        return new NotificationSummary(
                n.id(), n.eventType(), n.deliveryStatus().name(), n.attempts(),
                n.createdAt(), n.updatedAt(), n.nextRetryAt());
    }
}
