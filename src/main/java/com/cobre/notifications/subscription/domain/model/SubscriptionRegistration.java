package com.cobre.notifications.subscription.domain.model;

/**
 * The data needed to register (seed) a subscription, handed to
 * {@code RegisterSubscriptionUseCase}. A pre-persistence value: {@code id} and {@code createdAt}
 * are assigned by the database on insert, so they are absent here. {@code active} is coalesced to
 * {@code true} at the seed boundary when the source omits it.
 *
 * @param clientId  the owning client
 * @param eventType a concrete event type, or {@code "*"} for all of the client's events
 * @param targetUrl the webhook URL events for this subscription are delivered to
 * @param secret    the per-subscription HMAC secret (used for payload signing later), possibly {@code null}
 * @param active    whether the subscription is live
 */
public record SubscriptionRegistration(
        String clientId,
        String eventType,
        String targetUrl,
        String secret,
        boolean active) {
}
