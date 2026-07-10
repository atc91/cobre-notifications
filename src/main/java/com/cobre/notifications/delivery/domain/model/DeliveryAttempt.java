package com.cobre.notifications.delivery.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only audit record of a single delivery attempt against a {@link Notification}. Mirrors
 * the {@code delivery_attempts} table (P-02). One row is written per HTTP attempt, never mutated.
 *
 * @param id             attempt identity ({@code null} until the store assigns it)
 * @param notificationId the owning notification's id (= platform {@code event_id})
 * @param attemptNo      1-based ordinal of this attempt
 * @param attemptedAt    when the attempt was made
 * @param httpStatus     response status code, or {@code null} if the call never completed
 * @param error          failure detail, or {@code null} on success
 * @param durationMs     wall-clock duration of the attempt in milliseconds
 */
public record DeliveryAttempt(
        UUID id,
        String notificationId,
        int attemptNo,
        Instant attemptedAt,
        Integer httpStatus,
        String error,
        Long durationMs) {
}
