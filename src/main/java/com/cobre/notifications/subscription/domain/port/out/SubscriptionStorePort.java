package com.cobre.notifications.subscription.domain.port.out;

import com.cobre.notifications.subscription.domain.model.SubscriptionRegistration;
import reactor.core.publisher.Mono;

/**
 * Outbound port the subscription context needs to persist subscriptions. Implemented by the R2DBC
 * adapter with an idempotent upsert on the {@code (client_id, event_type)} unique key, so re-seeding
 * on restart adds no rows.
 */
public interface SubscriptionStorePort {

    /** Persist a subscription; a duplicate {@code (clientId, eventType)} is a harmless no-op. */
    Mono<Void> save(SubscriptionRegistration registration);
}
