package com.cobre.notifications.delivery.domain.port.in;

import com.cobre.notifications.delivery.domain.model.PlatformEvent;
import reactor.core.publisher.Mono;

/**
 * Inbound port: accept a platform event, confirm the client is subscribed, and persist a
 * {@code PENDING} notification. Driven by the JSON seed loader (P-04) and later the Kafka consumer
 * (P-14) — interchangeable driving adapters over the same use case.
 *
 * <p>Idempotent by design: the notification id is the platform {@code event_id}, so re-ingesting the
 * same event is a harmless no-op.
 */
public interface IngestEventUseCase {

    /** Ingest one platform event. Completes when the notification is persisted (or already existed). */
    Mono<Void> ingest(PlatformEvent event);
}
