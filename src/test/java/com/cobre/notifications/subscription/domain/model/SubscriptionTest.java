package com.cobre.notifications.subscription.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-domain unit test of the {@link Subscription#matches} client-scoping rule. No Spring. */
class SubscriptionTest {

    private static Subscription subscription(String clientId, String eventType, boolean active) {
        return new Subscription(UUID.randomUUID(), clientId, eventType,
                "https://client.example/webhook", null, active, Instant.now());
    }

    @Test
    void matchesExactClientAndEventType() {
        Subscription sub = subscription("CLIENT001", "credit_card_payment", true);
        assertThat(sub.matches("CLIENT001", "credit_card_payment")).isTrue();
    }

    @Test
    void wildcardMatchesAnyEventTypeForSameClient() {
        Subscription sub = subscription("CLIENT001", Subscription.WILDCARD, true);
        assertThat(sub.matches("CLIENT001", "credit_card_payment")).isTrue();
        assertThat(sub.matches("CLIENT001", "debit_transfer")).isTrue();
    }

    @Test
    void doesNotMatchDifferentEventType() {
        Subscription sub = subscription("CLIENT001", "credit_card_payment", true);
        assertThat(sub.matches("CLIENT001", "debit_transfer")).isFalse();
    }

    @Test
    void doesNotMatchDifferentClient() {
        Subscription sub = subscription("CLIENT001", "credit_card_payment", true);
        assertThat(sub.matches("CLIENT002", "credit_card_payment")).isFalse();
    }

    @Test
    void inactiveSubscriptionNeverMatches() {
        Subscription sub = subscription("CLIENT001", "credit_card_payment", false);
        assertThat(sub.matches("CLIENT001", "credit_card_payment")).isFalse();
    }
}
