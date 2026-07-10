package com.cobre.notifications.delivery.adapter.in;

import com.cobre.notifications.delivery.domain.model.PlatformEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Jackson view of one entry in {@code notification_events.json}, mapped onto the domain
 * {@link PlatformEvent}. The seed's {@code delivery_status} is deliberately <em>not</em> a field here:
 * every seeded event is ingested as {@code PENDING}, and the delivery lifecycle is driven only by the
 * pipeline (P-06+), never by seed data. {@code ignoreUnknown} lets that column (and any future ones) be
 * present in the file without breaking parsing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record EventSeedEntry(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("content") String content,
        @JsonProperty("delivery_date") Instant occurredAt,
        @JsonProperty("client_id") String clientId) {

    PlatformEvent toPlatformEvent() {
        return new PlatformEvent(eventId, eventType, content, clientId, occurredAt);
    }
}
