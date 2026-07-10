package com.cobre.notifications.delivery.adapter.in;

import com.cobre.notifications.delivery.domain.model.PlatformEvent;
import com.cobre.notifications.delivery.domain.port.in.IngestEventUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Driving adapter that seeds the notification pipeline from {@code notification_events.json} on startup:
 * it reads the bundled resource, maps each entry to a {@link PlatformEvent}, and drives the
 * {@link IngestEventUseCase} for each — the same use case a Kafka consumer will later drive (P-14),
 * unchanged.
 *
 * <p>Ingestion is idempotent (the store dedupes on {@code event_id}), so re-running on restart is safe.
 * Ordered after {@code SubscriptionSeedLoader} ({@code @Order(2)}) so subscriptions exist before events
 * are gated against them (P-05). Activation is gated by {@code notifications.seed.enabled} (default
 * {@code true}); the test profile disables it so {@code @SpringBootTest} contexts do not auto-seed.
 */
@Component
@Order(2) // after SubscriptionSeedLoader (@Order(1)): subscriptions must exist before events are gated
@ConditionalOnProperty(name = "notifications.seed.enabled", havingValue = "true", matchIfMissing = true)
public class EventSeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EventSeedLoader.class);

    private final IngestEventUseCase ingest;
    private final ObjectMapper objectMapper;
    private final Resource seedResource;

    public EventSeedLoader(
            IngestEventUseCase ingest,
            ObjectMapper objectMapper,
            @Value("${notifications.seed.location:classpath:notification_events.json}") Resource seedResource) {
        this.ingest = ingest;
        this.objectMapper = objectMapper;
        this.seedResource = seedResource;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed().subscribe(
                ingested -> log.info("event seed loader: ingested {} event(s) from {}",
                        ingested, seedResource.getDescription()),
                err -> log.error("event seed loader failed for {}", seedResource.getDescription(), err));
    }

    /**
     * The reactive seed pipeline: parse the resource, then ingest each event in order. Completes with the
     * count of successfully ingested events. Exposed (package-private) so integration tests can await it
     * deterministically instead of relying on the fire-and-forget startup subscription.
     */
    Mono<Long> seed() {
        List<PlatformEvent> events;
        try (InputStream in = seedResource.getInputStream()) {
            events = EventSeedParser.parse(in, objectMapper);
        } catch (IOException | JacksonException e) {
            log.error("event seed loader: cannot read seed resource {}", seedResource.getDescription(), e);
            return Mono.just(0L);
        }
        log.info("event seed loader: ingesting {} event(s) from {}", events.size(), seedResource.getDescription());
        return Flux.fromIterable(events)
                .concatMap(this::ingestOne)
                .count();
    }

    /** Ingest one event; a failure is logged and swallowed so one bad event never blocks the rest. */
    private Mono<PlatformEvent> ingestOne(PlatformEvent event) {
        return ingest.ingest(event)
                .thenReturn(event)
                .onErrorResume(ex -> {
                    log.warn("event seed loader: skipping event {}: {}", event.eventId(), ex.toString());
                    return Mono.empty();
                });
    }
}
