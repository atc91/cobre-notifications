package com.cobre.notifications.subscription.domain.port.in;

import com.cobre.notifications.subscription.domain.model.SubscriptionRegistration;
import reactor.core.publisher.Mono;

/**
 * Inbound port: register a subscription so future events for its client + event type are delivered.
 * Driven by the JSON subscription seed loader (P-05); a subscription-management API could drive the
 * same use case later.
 *
 * <p>Idempotent by contract — re-registering the same {@code (clientId, eventType)} is a harmless
 * no-op, so seeding on every restart is safe.
 */
public interface RegisterSubscriptionUseCase {

    /** Register one subscription. Completes when it is persisted (or already existed). */
    Mono<Void> register(SubscriptionRegistration registration);
}
