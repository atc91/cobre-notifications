package com.cobre.notifications.delivery.adapter.in;

import com.cobre.notifications.delivery.domain.model.PlatformEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the seed-file mapping. No Spring — it parses the bundled {@code notification_events.json}
 * with a hand-built {@link ObjectMapper} and asserts each entry maps to a {@link PlatformEvent} with the
 * right fields. The seed's {@code delivery_status} is proven to be dropped: {@code PlatformEvent} carries
 * no status, so ingest always starts {@code PENDING}.
 */
class SeedFileMapperTest {

    // Jackson 3 bundles java.time support in databind and registers it by default, so a plain
    // ObjectMapper already deserializes the ISO-8601 delivery_date into an Instant.
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesAllTenSeedEventsWithCorrectFieldMapping() throws IOException {
        List<PlatformEvent> events = parseSeedFile();

        assertThat(events).hasSize(10);

        PlatformEvent first = events.get(0);
        assertThat(first.eventId()).isEqualTo("EVT001");
        assertThat(first.eventType()).isEqualTo("credit_card_payment");
        assertThat(first.content()).isEqualTo("Credit card payment received for $150.00");
        assertThat(first.clientId()).isEqualTo("CLIENT001");
        assertThat(first.occurredAt()).isEqualTo(Instant.parse("2024-03-15T09:30:22Z")); // from delivery_date
    }

    @Test
    void dropsTheSeedDeliveryStatus() throws IOException {
        // EVT003 is "failed" in the seed file; the mapped event carries only the fields PlatformEvent
        // defines — there is no status to leak onto ingest, so every event will be persisted as PENDING.
        PlatformEvent failedInSeed = parseSeedFile().stream()
                .filter(e -> e.eventId().equals("EVT003"))
                .findFirst()
                .orElseThrow();

        assertThat(failedInSeed.eventType()).isEqualTo("credit_transfer");
        assertThat(failedInSeed.clientId()).isEqualTo("CLIENT002");
        // PlatformEvent has no status/delivery_status component — the seed column is structurally dropped.
        assertThat(PlatformEvent.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder("eventId", "eventType", "content", "clientId", "occurredAt");
    }

    private List<PlatformEvent> parseSeedFile() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/notification_events.json")) {
            assertThat(in).as("notification_events.json on the classpath").isNotNull();
            return SeedFileParser.parse(in, mapper);
        }
    }
}
