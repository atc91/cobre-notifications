package com.cobre.notifications.delivery.domain.model;

import java.time.Instant;

/**
 * A platform event handed to {@code IngestEventUseCase} for delivery. Its shape mirrors an entry in
 * {@code notification_events.json}; the same record will later carry a Kafka message's payload,
 * proving the seed loader and the broker are interchangeable driving adapters.
 *
 * @param eventId    the platform event id; becomes the {@link Notification} id / idempotency key
 * @param eventType  the concrete event type (e.g. {@code credit_card_payment})
 * @param content    the human-readable notification content
 * @param clientId   the owning client
 * @param occurredAt when the event happened on the platform
 */
public record PlatformEvent(
        String eventId,
        String eventType,
        String content,
        String clientId,
        Instant occurredAt) {
}
