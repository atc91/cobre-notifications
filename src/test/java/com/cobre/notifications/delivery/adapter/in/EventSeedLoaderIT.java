package com.cobre.notifications.delivery.adapter.in;

import com.cobre.notifications.TestcontainersConfiguration;
import com.cobre.notifications.delivery.domain.port.in.IngestEventUseCase;
import com.cobre.notifications.subscription.adapter.in.SubscriptionSeedLoader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the event seed loader end to end through the subscription gate (file → use case →
 * real PostgreSQL). Both seed loaders are enabled; the test seeds subscriptions first, then events, so
 * ingest can resolve each event against a subscription. It proves the gate persists only subscribed
 * events (9 of 10) and skips the uncovered one (EVT009, CLIENT003/credit_cashback) — no cross-client
 * leakage — and that re-running stays idempotent. Also asserts the {@code notifications.seed.enabled}
 * flag gates the loader bean.
 */
@SpringBootTest(properties = {
        "notifications.seed.enabled=true",
        "notifications.subscription-seed.enabled=true"
})
@Import(TestcontainersConfiguration.class)
class EventSeedLoaderIT {

    @Autowired
    DatabaseClient db;

    @Autowired
    EventSeedLoader loader;

    @Autowired
    SubscriptionSeedLoader subscriptionLoader;

    @Test
    void gatesIngestToSubscribedEventsOnly() {
        // Subscriptions first: the gate needs subscription data to resolve each event against.
        StepVerifier.create(subscriptionLoader.seed()).expectNext(4L).verifyComplete();
        // All 10 events are processed by the loader; the gate persists 9 and skips EVT009
        // (CLIENT003/credit_cashback, which has no subscription).
        StepVerifier.create(loader.seed()).expectNext(10L).verifyComplete();

        StepVerifier.create(count("SELECT count(*) FROM notifications"))
                .expectNext(9L).verifyComplete();
        StepVerifier.create(count("SELECT count(*) FROM notifications WHERE delivery_status = 'PENDING'"))
                .expectNext(9L).verifyComplete();
        // The unsubscribed event never lands — no cross-client leakage.
        StepVerifier.create(count(
                "SELECT count(*) FROM notifications WHERE client_id = 'CLIENT003' AND event_type = 'credit_cashback'"))
                .expectNext(0L).verifyComplete();
        // Every persisted notification carries a target_url resolved from its subscription (no longer null).
        StepVerifier.create(count("SELECT count(*) FROM notifications WHERE target_url IS NULL"))
                .expectNext(0L).verifyComplete();
    }

    @Test
    void reRunningTheLoaderIsIdempotent() {
        StepVerifier.create(subscriptionLoader.seed()).expectNext(4L).verifyComplete();
        // Two full event runs; ingest dedupes on event_id, so the table still holds exactly the 9 gated rows.
        StepVerifier.create(loader.seed()).expectNext(10L).verifyComplete();
        StepVerifier.create(loader.seed()).expectNext(10L).verifyComplete();

        StepVerifier.create(count("SELECT count(*) FROM notifications"))
                .expectNext(9L).verifyComplete();
    }

    @Test
    void loaderBeanIsGatedByTheEnabledFlag() {
        IngestEventUseCase noop = event -> Mono.empty();
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(IngestEventUseCase.class, () -> noop)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(EventSeedLoader.class);

        runner.run(ctx -> assertThat(ctx).hasSingleBean(EventSeedLoader.class));                        // default on
        runner.withPropertyValues("notifications.seed.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(EventSeedLoader.class));
        runner.withPropertyValues("notifications.seed.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(EventSeedLoader.class));
    }

    private Mono<Long> count(String sql) {
        return db.sql(sql).map(row -> row.get(0, Long.class)).one();
    }
}
