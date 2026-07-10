package com.cobre.notifications.delivery.domain.model;

/**
 * The result of resolving a client + event type against the subscription context, returned by
 * {@code SubscriptionPort}. This is a <em>delivery-owned</em> record on purpose: the {@code delivery}
 * hexagon must never import the {@code subscription} context's {@code Subscription} entity (the
 * Dependency Rule), so the port hands back only the two fields delivery needs.
 *
 * @param targetUrl the webhook URL to deliver to
 * @param secret    the per-subscription HMAC secret (used for payload signing in a later phase),
 *                  possibly {@code null}
 */
public record SubscriptionLookup(String targetUrl, String secret) {
}
