package com.cobre.notifications.subscription.adapter.in;

import com.cobre.notifications.subscription.domain.model.SubscriptionRegistration;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Jackson view of one entry in {@code subscriptions.json}, mapped onto {@link SubscriptionRegistration}.
 * {@code active} is optional and defaults to {@code true} when absent. {@code ignoreUnknown} tolerates
 * extra columns (and any future ones) without breaking parsing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record SubscriptionSeedEntry(
        @JsonProperty("client_id") String clientId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("target_url") String targetUrl,
        @JsonProperty("secret") String secret,
        @JsonProperty("active") Boolean active) {

    SubscriptionRegistration toRegistration() {
        return new SubscriptionRegistration(clientId, eventType, targetUrl, secret, active == null || active);
    }
}
