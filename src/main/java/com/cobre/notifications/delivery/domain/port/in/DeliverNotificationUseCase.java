package com.cobre.notifications.delivery.domain.port.in;

import reactor.core.publisher.Mono;

/**
 * Inbound port: attempt delivery of the notifications currently due (status {@code PENDING} or
 * {@code RETRYING} with {@code nextRetryAt <= now}). Driven by the {@code DueDeliveryScheduler}
 * (P-07). Each due row is claimed, POSTed to its webhook, and advanced to {@code DELIVERED},
 * {@code RETRYING}, or {@code FAILED}, with a {@code DeliveryAttempt} recorded per attempt.
 */
public interface DeliverNotificationUseCase {

    /** Claim and attempt every currently-due notification. Completes when the batch is processed. */
    Mono<Void> deliverDue();
}
