package com.cobre.notifications.delivery.application;

import com.cobre.notifications.delivery.domain.model.Notification;
import com.cobre.notifications.delivery.domain.model.PlatformEvent;
import com.cobre.notifications.delivery.domain.port.in.IngestEventUseCase;
import com.cobre.notifications.delivery.domain.port.out.ClockPort;
import com.cobre.notifications.delivery.domain.port.out.NotificationStorePort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Use-case implementation for ingesting a platform event. Persist-first: it builds a fresh
 * {@code PENDING} {@link Notification} and hands it to the {@link NotificationStorePort}. The webhook
 * is never called here — the persisted row is the single source of truth (inbox pattern), and delivery
 * is a later phase acting on that row.
 *
 * <p>Idempotency is delegated to the store's {@code INSERT ... ON CONFLICT (id) DO NOTHING}: re-ingesting
 * the same {@code event_id} is a harmless no-op, so at-least-once redelivery (later Kafka) and seed-loader
 * restarts are safe.
 *
 * <p>{@code targetUrl} is left {@code null} here; the subscription gate resolves it in P-05.
 */
@Service
public class IngestEventService implements IngestEventUseCase {

    private final NotificationStorePort store;
    private final ClockPort clock;

    public IngestEventService(NotificationStorePort store, ClockPort clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public Mono<Void> ingest(PlatformEvent event) {
        return Mono.fromSupplier(() -> Notification.pending(
                        event.eventId(),
                        event.clientId(),
                        event.eventType(),
                        event.content(),
                        null, // targetUrl is resolved by the subscription gate in P-05
                        clock.now()))
                .flatMap(store::save)
                .then();
    }
}
