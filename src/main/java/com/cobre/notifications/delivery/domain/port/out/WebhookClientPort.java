package com.cobre.notifications.delivery.domain.port.out;

import com.cobre.notifications.delivery.domain.model.DeliveryOutcome;
import com.cobre.notifications.delivery.domain.model.WebhookRequest;
import reactor.core.publisher.Mono;

/**
 * Outbound port for the one external side effect: an HTTPS POST of the payload to the client's
 * webhook. Implemented by the WebClient adapter (P-06), which enforces the SSRF guard (https-only,
 * no redirects, blocks private/loopback/metadata ranges) and strict timeouts. Never throws for a
 * delivery failure — a non-2xx or transport error is returned as a failed {@link DeliveryOutcome}.
 */
public interface WebhookClientPort {

    /** POST the request to the client webhook and return the outcome (success or failure). */
    Mono<DeliveryOutcome> post(WebhookRequest request);
}
