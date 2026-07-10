package com.cobre.notifications.delivery.domain.model;

/**
 * The outcome of a webhook call, returned by {@code WebhookClientPort}. The application layer turns
 * a {@code success} into {@link Notification#recordSuccess} and a failure into
 * {@link Notification#recordFailure}, and writes a {@link DeliveryAttempt} either way.
 *
 * @param httpStatus the response status code, or {@code null} if the call never completed
 * @param success    true only on a 2xx response
 * @param error      failure detail (timeout, connection error, non-2xx), or {@code null} on success
 * @param durationMs wall-clock duration of the call in milliseconds
 */
public record DeliveryOutcome(
        Integer httpStatus,
        boolean success,
        String error,
        long durationMs) {

    public static DeliveryOutcome succeeded(int httpStatus, long durationMs) {
        return new DeliveryOutcome(httpStatus, true, null, durationMs);
    }

    public static DeliveryOutcome failed(Integer httpStatus, String error, long durationMs) {
        return new DeliveryOutcome(httpStatus, false, error, durationMs);
    }
}
