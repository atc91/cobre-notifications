package com.cobre.notifications.delivery.adapter.in;

import com.cobre.notifications.delivery.domain.model.PlatformEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code notification_events.json} into a list of {@link PlatformEvent}s. Parsing is tolerant:
 * a single malformed entry is logged and skipped rather than aborting the whole seed, so one bad row
 * never blocks the rest.
 */
final class EventSeedParser {

    private static final Logger log = LoggerFactory.getLogger(EventSeedParser.class);

    private EventSeedParser() {
    }

    /** Read the {@code events} array from the stream; skip (and log) any entry that fails to bind. */
    static List<PlatformEvent> parse(InputStream in, ObjectMapper mapper) {
        JsonNode events = mapper.readTree(in).path("events");
        if (!events.isArray()) {
            log.warn("event seed: no 'events' array found; nothing to ingest");
            return List.of();
        }
        List<PlatformEvent> result = new ArrayList<>();
        for (JsonNode node : events) {
            try {
                result.add(mapper.treeToValue(node, EventSeedEntry.class).toPlatformEvent());
            } catch (JacksonException e) {
                log.warn("event seed: skipping malformed entry {}: {}", node, e.getMessage());
            }
        }
        return result;
    }
}
