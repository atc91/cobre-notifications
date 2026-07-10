package com.cobre.notifications.delivery.domain.port.out;

import reactor.core.publisher.Mono;

/**
 * Delivery-owned outbound port that re-enqueues a dead-lettered notification for the {@code query}
 * context (P-11). The whole guard-and-transition runs inside the adapter's transaction so the check
 * ("is it owned? is it FAILED?") and the {@code FAILED → PENDING} write are atomic; the aggregate
 * ({@code Notification.replay}) remains the sole author of the status change.
 */
public interface NotificationReplayPort {

    /**
     * Re-enqueue the {@code FAILED} notification {@code id} owned by {@code clientId}: reset it to
     * {@code PENDING}, due now. Signals {@code NotFoundException} when absent or not owned (before any
     * status check, so ownership never leaks) and {@code ConflictException} when it exists but is not
     * {@code FAILED}.
     */
    Mono<Void> replay(String id, String clientId);
}
