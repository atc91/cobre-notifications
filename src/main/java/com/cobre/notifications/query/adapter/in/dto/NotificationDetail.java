package com.cobre.notifications.query.adapter.in.dto;

import com.cobre.notifications.query.domain.model.NotificationWithAttempts;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Full single-notification view for {@code GET /notification_events/{id}} (P-10): the aggregate's
 * fields plus its append-only {@code delivery_attempts} history. {@link JsonProperty} pins the
 * snake_case wire names of the API contract.
 */
public record NotificationDetail(
        String id,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("event_type") String eventType,
        String content,
        @JsonProperty("target_url") String targetUrl,
        @JsonProperty("delivery_status") String deliveryStatus,
        int attempts,
        @JsonProperty("next_retry_at") Instant nextRetryAt,
        @JsonProperty("last_error") String lastError,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("delivered_at") Instant deliveredAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("delivery_attempts") List<DeliveryAttemptView> deliveryAttempts) {

    public static NotificationDetail from(NotificationWithAttempts view) {
        var n = view.notification();
        List<DeliveryAttemptView> attempts = view.attempts().stream()
                .map(DeliveryAttemptView::from)
                .toList();
        return new NotificationDetail(
                n.id(), n.clientId(), n.eventType(), n.content(), n.targetUrl(),
                n.deliveryStatus().name(), n.attempts(), n.nextRetryAt(), n.lastError(),
                n.createdAt(), n.deliveredAt(), n.updatedAt(), attempts);
    }
}
