package com.cobre.notifications.delivery.domain.port.out;

import com.cobre.notifications.delivery.domain.model.SubscriptionLookup;
import reactor.core.publisher.Mono;

/**
 * Outbound port answering "is client {@code C} subscribed to event type {@code E}, and to which
 * URL?". Implemented in P-05 by the subscription context's R2DBC adapter, which applies
 * {@code Subscription.matches(...)}. An empty {@link Mono} means the client is <em>not</em>
 * subscribed, so the event must not be delivered (no cross-client leakage).
 */
public interface SubscriptionPort {

    /** Resolve the target for a client + event type, or empty if there is no active subscription. */
    Mono<SubscriptionLookup> resolve(String clientId, String eventType);
}
