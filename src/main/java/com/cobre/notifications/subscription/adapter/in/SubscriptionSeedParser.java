package com.cobre.notifications.subscription.adapter.in;

import com.cobre.notifications.subscription.domain.model.SubscriptionRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code subscriptions.json} into a list of {@link SubscriptionRegistration}s. Parsing is
 * tolerant: a single malformed entry is logged and skipped rather than aborting the whole seed, so one
 * bad row never blocks the rest.
 */
final class SubscriptionSeedParser {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionSeedParser.class);

    private SubscriptionSeedParser() {
    }

    /** Read the {@code subscriptions} array from the stream; skip (and log) any entry that fails to bind. */
    static List<SubscriptionRegistration> parse(InputStream in, ObjectMapper mapper) {
        JsonNode subscriptions = mapper.readTree(in).path("subscriptions");
        if (!subscriptions.isArray()) {
            log.warn("subscription seed: no 'subscriptions' array found; nothing to register");
            return List.of();
        }
        List<SubscriptionRegistration> result = new ArrayList<>();
        for (JsonNode node : subscriptions) {
            try {
                result.add(mapper.treeToValue(node, SubscriptionSeedEntry.class).toRegistration());
            } catch (JacksonException e) {
                log.warn("subscription seed: skipping malformed entry {}: {}", node, e.getMessage());
            }
        }
        return result;
    }
}
