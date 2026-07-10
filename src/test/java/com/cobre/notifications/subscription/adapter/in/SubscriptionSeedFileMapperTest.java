package com.cobre.notifications.subscription.adapter.in;

import com.cobre.notifications.subscription.domain.model.SubscriptionRegistration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the subscription seed-file mapping. No Spring — it parses the bundled
 * {@code subscriptions.json} with a hand-built {@link ObjectMapper} and asserts each entry maps to a
 * {@link SubscriptionRegistration} with the right fields, the {@code "*"} wildcard preserved, and
 * {@code active} defaulting to {@code true}. The coverage deliberately omits CLIENT003/credit_cashback
 * (EVT009), which is what makes the gate skip that event end to end.
 */
class SubscriptionSeedFileMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesAllSeedSubscriptionsWithCorrectFieldMapping() throws IOException {
        List<SubscriptionRegistration> subs = parseSeedFile();

        assertThat(subs).hasSize(4);

        SubscriptionRegistration first = subs.get(0);
        assertThat(first.clientId()).isEqualTo("CLIENT001");
        assertThat(first.eventType()).isEqualTo("*");
        assertThat(first.targetUrl()).isEqualTo("https://client1.example.com/webhooks/cobre");
        assertThat(first.secret()).isEqualTo("whsec_client001");
        assertThat(first.active()).isTrue();

        // CLIENT003 is covered only for two concrete types; credit_cashback (EVT009) is intentionally absent.
        assertThat(subs).filteredOn(s -> s.clientId().equals("CLIENT003"))
                .extracting(SubscriptionRegistration::eventType)
                .containsExactlyInAnyOrder("credit_refund", "debit_transfer");
    }

    @Test
    void activeDefaultsToTrueForEverySeededRow() throws IOException {
        assertThat(parseSeedFile()).allMatch(SubscriptionRegistration::active);
    }

    private List<SubscriptionRegistration> parseSeedFile() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/subscriptions.json")) {
            assertThat(in).as("subscriptions.json on the classpath").isNotNull();
            return SubscriptionSeedParser.parse(in, mapper);
        }
    }
}
