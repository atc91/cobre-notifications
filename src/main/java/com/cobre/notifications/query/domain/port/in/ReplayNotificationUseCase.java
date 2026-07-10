package com.cobre.notifications.query.domain.port.in;

import reactor.core.publisher.Mono;

/**
 * Inbound port of the {@code query} context: re-enqueue a client's dead-lettered notification (P-11).
 * Driven by {@code NotificationEventController}. Delegates the ownership + status guard and the
 * {@code FAILED → PENDING} transition to the delivery replay port, which owns the aggregate.
 */
public interface ReplayNotificationUseCase {

    /**
     * Replay the {@code FAILED} notification {@code id} owned by {@code clientId}. Signals
     * {@code NotFoundException} when absent/not owned and {@code ConflictException} when not {@code FAILED}.
     */
    Mono<Void> replay(String clientId, String id);
}
