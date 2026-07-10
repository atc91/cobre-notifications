package com.cobre.notifications.query.adapter.in.dto;

import com.cobre.notifications.delivery.domain.model.DeliveryAttempt;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * One row of a notification's delivery-attempt history in the detail view (P-10). {@code httpStatus},
 * {@code error}, and {@code durationMs} may be {@code null} when a call never completed.
 */
public record DeliveryAttemptView(
        @JsonProperty("attempt_no") int attemptNo,
        @JsonProperty("attempted_at") Instant attemptedAt,
        @JsonProperty("http_status") Integer httpStatus,
        String error,
        @JsonProperty("duration_ms") Long durationMs) {

    public static DeliveryAttemptView from(DeliveryAttempt a) {
        return new DeliveryAttemptView(
                a.attemptNo(), a.attemptedAt(), a.httpStatus(), a.error(), a.durationMs());
    }
}
