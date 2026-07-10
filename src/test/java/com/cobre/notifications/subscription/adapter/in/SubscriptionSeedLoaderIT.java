package com.cobre.notifications.subscription.adapter.in;

import com.cobre.notifications.TestcontainersConfiguration;
import com.cobre.notifications.subscription.domain.port.in.RegisterSubscriptionUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the subscription seed loader end to end (file → use case → real PostgreSQL). Enables
 * the loader explicitly (the test profile disables it by default) and drives
 * {@link SubscriptionSeedLoader#seed()} deterministically rather than the fire-and-forget startup
 * subscription. Also asserts the {@code notifications.subscription-seed.enabled} flag gates the bean.
 */
@SpringBootTest(properties = "notifications.subscription-seed.enabled=true")
@Import(TestcontainersConfiguration.class)
class SubscriptionSeedLoaderIT {

    @Autowired
    DatabaseClient db;

    @Autowired
    SubscriptionSeedLoader loader;

    @Test
    void seedsAllFourSubscriptions() {
        StepVerifier.create(loader.seed()).expectNext(4L).verifyComplete();

        StepVerifier.create(count("SELECT count(*) FROM subscriptions"))
                .expectNext(4L).verifyComplete();
    }

    @Test
    void reRunningTheLoaderIsIdempotent() {
        // Two full runs; registration upserts on (client_id, event_type), so the table still holds 4 rows.
        StepVerifier.create(loader.seed()).expectNext(4L).verifyComplete();
        StepVerifier.create(loader.seed()).expectNext(4L).verifyComplete();

        StepVerifier.create(count("SELECT count(*) FROM subscriptions"))
                .expectNext(4L).verifyComplete();
    }

    @Test
    void loaderBeanIsGatedByTheEnabledFlag() {
        RegisterSubscriptionUseCase noop = registration -> Mono.empty();
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(RegisterSubscriptionUseCase.class, () -> noop)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(SubscriptionSeedLoader.class);

        runner.run(ctx -> assertThat(ctx).hasSingleBean(SubscriptionSeedLoader.class));                 // default on
        runner.withPropertyValues("notifications.subscription-seed.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(SubscriptionSeedLoader.class));
        runner.withPropertyValues("notifications.subscription-seed.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SubscriptionSeedLoader.class));
    }

    private Mono<Long> count(String sql) {
        return db.sql(sql).map(row -> row.get(0, Long.class)).one();
    }
}
