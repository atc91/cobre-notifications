package com.cobre.notifications.delivery.adapter.in;

import com.cobre.notifications.TestcontainersConfiguration;
import com.cobre.notifications.delivery.domain.port.in.IngestEventUseCase;
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
 * Integration test for the JSON seed loader end to end (file → use case → real PostgreSQL). Enables the
 * loader explicitly (the test profile disables it by default) and drives the seed pipeline deterministically
 * via {@link JsonSeedLoader#seed()} rather than the fire-and-forget startup subscription. Also asserts the
 * {@code notifications.seed.enabled} flag gates the bean.
 */
@SpringBootTest(properties = "notifications.seed.enabled=true")
@Import(TestcontainersConfiguration.class)
class JsonSeedLoaderIT {

    @Autowired
    DatabaseClient db;

    @Autowired
    JsonSeedLoader loader;

    @Test
    void seedsAllTenEventsAsPending() {
        StepVerifier.create(loader.seed()).expectNext(10L).verifyComplete();

        StepVerifier.create(count("SELECT count(*) FROM notifications"))
                .expectNext(10L).verifyComplete();
        StepVerifier.create(count("SELECT count(*) FROM notifications WHERE delivery_status = 'PENDING'"))
                .expectNext(10L).verifyComplete();
    }

    @Test
    void reRunningTheLoaderIsIdempotent() {
        // Two full runs; ingest dedupes on event_id, so the table still holds exactly the 10 seed rows.
        StepVerifier.create(loader.seed()).expectNext(10L).verifyComplete();
        StepVerifier.create(loader.seed()).expectNext(10L).verifyComplete();

        StepVerifier.create(count("SELECT count(*) FROM notifications"))
                .expectNext(10L).verifyComplete();
    }

    @Test
    void loaderBeanIsGatedByTheEnabledFlag() {
        IngestEventUseCase noop = event -> Mono.empty();
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(IngestEventUseCase.class, () -> noop)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(JsonSeedLoader.class);

        runner.run(ctx -> assertThat(ctx).hasSingleBean(JsonSeedLoader.class));                       // default on
        runner.withPropertyValues("notifications.seed.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(JsonSeedLoader.class));
        runner.withPropertyValues("notifications.seed.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(JsonSeedLoader.class));
    }

    private Mono<Long> count(String sql) {
        return db.sql(sql).map(row -> row.get(0, Long.class)).one();
    }
}
