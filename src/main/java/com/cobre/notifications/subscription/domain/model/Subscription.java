package com.cobre.notifications.subscription.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A client's subscription to platform events, owned by the {@code subscription} context. Mirrors the
 * {@code subscriptions} table (P-02). {@link #eventType} is either a concrete type or the wildcard
 * {@code "*"} meaning "every event type for this client".
 *
 * <p>The {@link #matches} rule is the gate that keeps delivery client-scoped: an event is delivered
 * only when an active subscription for that client covers its type. This entity never crosses into
 * the {@code delivery} context — the {@code SubscriptionPort} returns a delivery-owned lookup result.
 */
public record Subscription(
        UUID id,
        String clientId,
        String eventType,
        String targetUrl,
        String secret,
        boolean active,
        Instant createdAt) {

    /** The wildcard event type: a subscription that covers every event type for its client. */
    public static final String WILDCARD = "*";

    /**
     * True when this subscription should receive an event for {@code clientId} of type
     * {@code eventType}: it must be {@link #active}, belong to the same client, and either match the
     * event type exactly or be a {@link #WILDCARD} subscription.
     */
    public boolean matches(String clientId, String eventType) {
        return active
                && this.clientId.equals(clientId)
                && (WILDCARD.equals(this.eventType) || this.eventType.equals(eventType));
    }
}
