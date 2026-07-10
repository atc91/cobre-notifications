package com.cobre.notifications.delivery.application;

import com.cobre.notifications.delivery.domain.model.Notification;
import com.cobre.notifications.delivery.domain.model.PlatformEvent;
import com.cobre.notifications.delivery.domain.port.in.IngestEventUseCase;
import com.cobre.notifications.delivery.domain.port.out.ClockPort;
import com.cobre.notifications.delivery.domain.port.out.NotificationStorePort;
import com.cobre.notifications.delivery.domain.port.out.SubscriptionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Use-case implementation for ingesting a platform event, gated by subscription (P-05). It first asks
 * the {@link SubscriptionPort} whether the event's client is subscribed to that event type; only on a
 * match does it persist. Persist-first: it builds a fresh {@code PENDING} {@link Notification} carrying
 * the resolved {@code targetUrl} and hands it to the {@link NotificationStorePort}. The webhook is never
 * called here — the persisted row is the single source of truth (inbox pattern), and delivery is a later
 * phase acting on that row.
 *
 * <p>An event with <em>no</em> active subscription is <strong>skipped</strong>: nothing is persisted and
 * no error is raised (a not-subscribed event is expected, not a failure), so no cross-client delivery can
 * ever happen. The skip branch is safe because {@link NotificationStorePort#save} emits a value on
 * success — {@code switchIfEmpty} fires only when {@code resolve} itself is empty, never after a save.
 *
 * <p>Idempotency is delegated to the store's {@code INSERT ... ON CONFLICT (id) DO NOTHING}: re-ingesting
 * the same {@code event_id} is a harmless no-op, so at-least-once redelivery (later Kafka) and seed-loader
 * restarts are safe.
 */
@Service
public class IngestEventService implements IngestEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngestEventService.class);

    private final SubscriptionPort subscriptions;
    private final NotificationStorePort store;
    private final ClockPort clock;

    public IngestEventService(SubscriptionPort subscriptions, NotificationStorePort store, ClockPort clock) {
        this.subscriptions = subscriptions;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public Mono<Void> ingest(PlatformEvent event) {
        return subscriptions.resolve(event.clientId(), event.eventType())
                .flatMap(lookup -> store.save(Notification.pending(
                        event.eventId(),
                        event.clientId(),
                        event.eventType(),
                        event.content(),
                        lookup.targetUrl(),
                        clock.now())))
                .switchIfEmpty(Mono.<Notification>fromRunnable(() -> log.info(
                        "ingest: no active subscription for {}/{} — skipping event {}",
                        event.clientId(), event.eventType(), event.eventId())))
                .then();
    }
}
