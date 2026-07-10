package com.cobre.notifications.subscription.adapter.in;

import com.cobre.notifications.subscription.domain.model.SubscriptionRegistration;
import com.cobre.notifications.subscription.domain.port.in.RegisterSubscriptionUseCase;
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
 * Driving adapter that seeds the {@code subscriptions} table from {@code subscriptions.json} on startup:
 * it reads the bundled resource, maps each entry to a {@link SubscriptionRegistration}, and drives the
 * {@link RegisterSubscriptionUseCase} for each.
 *
 * <p>Ordered ahead of the event seed loader ({@code @Order(1)}) so subscriptions exist before events are
 * gated against them. Registration is idempotent (upsert on the unique key), so re-running on restart is
 * safe. Activation is gated by {@code notifications.subscription-seed.enabled} (default {@code true}); the
 * test profile disables it so {@code @SpringBootTest} contexts do not auto-seed.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "notifications.subscription-seed.enabled", havingValue = "true", matchIfMissing = true)
public class SubscriptionSeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionSeedLoader.class);

    private final RegisterSubscriptionUseCase register;
    private final ObjectMapper objectMapper;
    private final Resource seedResource;

    public SubscriptionSeedLoader(
            RegisterSubscriptionUseCase register,
            ObjectMapper objectMapper,
            @Value("${notifications.subscription-seed.location:classpath:subscriptions.json}") Resource seedResource) {
        this.register = register;
        this.objectMapper = objectMapper;
        this.seedResource = seedResource;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed().subscribe(
                registered -> log.info("subscription seed loader: registered {} subscription(s) from {}",
                        registered, seedResource.getDescription()),
                err -> log.error("subscription seed loader failed for {}", seedResource.getDescription(), err));
    }

    /**
     * The reactive seed pipeline: parse the resource, then register each subscription in order. Completes
     * with the count of successfully registered subscriptions. Public so integration tests — including the
     * delivery gate's end-to-end test in another context — can await it deterministically instead of
     * relying on the fire-and-forget startup subscription.
     */
    public Mono<Long> seed() {
        List<SubscriptionRegistration> subscriptions;
        try (InputStream in = seedResource.getInputStream()) {
            subscriptions = SubscriptionSeedParser.parse(in, objectMapper);
        } catch (IOException | JacksonException e) {
            log.error("subscription seed loader: cannot read seed resource {}", seedResource.getDescription(), e);
            return Mono.just(0L);
        }
        log.info("subscription seed loader: registering {} subscription(s) from {}",
                subscriptions.size(), seedResource.getDescription());
        return Flux.fromIterable(subscriptions)
                .concatMap(this::registerOne)
                .count();
    }

    /** Register one subscription; a failure is logged and swallowed so one bad row never blocks the rest. */
    private Mono<SubscriptionRegistration> registerOne(SubscriptionRegistration subscription) {
        return register.register(subscription)
                .thenReturn(subscription)
                .onErrorResume(ex -> {
                    log.warn("subscription seed loader: skipping {}/{}: {}",
                            subscription.clientId(), subscription.eventType(), ex.toString());
                    return Mono.empty();
                });
    }
}
