package com.cobre.notifications.subscription.adapter.out;

import com.cobre.notifications.TestcontainersConfiguration;
import com.cobre.notifications.delivery.domain.port.out.SubscriptionPort;
import com.cobre.notifications.subscription.domain.model.SubscriptionRegistration;
import com.cobre.notifications.subscription.domain.port.out.SubscriptionStorePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the R2DBC subscription adapter against a real PostgreSQL (Testcontainers). Pins the
 * SQL contract of the gate: active + client-scoped + exact/wildcard resolution (exact wins over {@code '*'}),
 * plus the idempotent upsert used by the seed loader. Each test uses a distinct client id so the tests stay
 * isolated within the shared schema.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class R2dbcSubscriptionRepositoryIT {

    @Autowired
    SubscriptionPort resolve;

    @Autowired
    SubscriptionStorePort store;

    @Autowired
    DatabaseClient db;

    @Test
    void exactMatchResolvesTargetUrlAndSecret() {
        save("C-EXACT", "credit_refund", "https://c-exact.example/hook", "sek", true);

        StepVerifier.create(resolve.resolve("C-EXACT", "credit_refund"))
                .assertNext(lookup -> {
                    assertThat(lookup.targetUrl()).isEqualTo("https://c-exact.example/hook");
                    assertThat(lookup.secret()).isEqualTo("sek");
                })
                .verifyComplete();
    }

    @Test
    void wildcardMatchesAnyEventTypeForTheClient() {
        save("C-WILD", "*", "https://c-wild.example/hook", null, true);

        StepVerifier.create(resolve.resolve("C-WILD", "any_event_type_at_all"))
                .assertNext(lookup -> assertThat(lookup.targetUrl()).isEqualTo("https://c-wild.example/hook"))
                .verifyComplete();
    }

    @Test
    void exactMatchWinsOverWildcard() {
        save("C-BOTH", "*", "https://c-both.example/wildcard", null, true);
        save("C-BOTH", "credit_refund", "https://c-both.example/exact", null, true);

        StepVerifier.create(resolve.resolve("C-BOTH", "credit_refund"))
                .assertNext(lookup -> assertThat(lookup.targetUrl()).isEqualTo("https://c-both.example/exact"))
                .verifyComplete();
    }

    @Test
    void inactiveSubscriptionResolvesEmpty() {
        save("C-INACTIVE", "credit_refund", "https://c-inactive.example/hook", null, false);

        StepVerifier.create(resolve.resolve("C-INACTIVE", "credit_refund")).verifyComplete();
    }

    @Test
    void differentClientResolvesEmpty() {
        save("C-OWNER", "credit_refund", "https://c-owner.example/hook", null, true);

        // A different client must never resolve another client's subscription (no cross-client leakage).
        StepVerifier.create(resolve.resolve("C-INTRUDER", "credit_refund")).verifyComplete();
    }

    @Test
    void unknownEventTypeWithoutWildcardResolvesEmpty() {
        save("C-NARROW", "credit_refund", "https://c-narrow.example/hook", null, true);

        StepVerifier.create(resolve.resolve("C-NARROW", "debit_transfer")).verifyComplete();
    }

    @Test
    void saveIsIdempotentOnClientAndEventType() {
        SubscriptionRegistration first =
                new SubscriptionRegistration("C-DUP", "credit_refund", "https://c-dup.example/one", null, true);
        SubscriptionRegistration duplicate =
                new SubscriptionRegistration("C-DUP", "credit_refund", "https://c-dup.example/two", null, true);

        // Re-saving the same (client_id, event_type) must neither error nor duplicate: ON CONFLICT DO NOTHING.
        StepVerifier.create(store.save(first).then(store.save(duplicate))).verifyComplete();

        StepVerifier.create(db.sql("SELECT count(*) FROM subscriptions WHERE client_id = 'C-DUP'")
                        .map(row -> row.get(0, Long.class)).one())
                .assertNext(count -> assertThat(count).isEqualTo(1L))
                .verifyComplete();
        // The original row survives the conflict untouched.
        StepVerifier.create(resolve.resolve("C-DUP", "credit_refund"))
                .assertNext(lookup -> assertThat(lookup.targetUrl()).isEqualTo("https://c-dup.example/one"))
                .verifyComplete();
    }

    private void save(String clientId, String eventType, String targetUrl, String secret, boolean active) {
        store.save(new SubscriptionRegistration(clientId, eventType, targetUrl, secret, active))
                .as(StepVerifier::create).verifyComplete();
    }
}
