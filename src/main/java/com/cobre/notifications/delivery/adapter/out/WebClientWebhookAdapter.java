package com.cobre.notifications.delivery.adapter.out;

import com.cobre.notifications.delivery.domain.model.DeliveryOutcome;
import com.cobre.notifications.delivery.domain.model.WebhookRequest;
import com.cobre.notifications.delivery.domain.port.out.WebhookClientPort;
import io.netty.channel.ChannelOption;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Driven adapter implementing {@link WebhookClientPort} over a reactive {@link WebClient} (P-06).
 * The client is configured with the connect/read timeouts from {@link WebhookProperties} and
 * <strong>no redirect following</strong>; every target is screened by the {@link SsrfGuard} first.
 *
 * <p>Per the port contract this <em>never throws</em> for a delivery failure: a non-2xx response,
 * timeout, transport error, or SSRF block is mapped to a failed {@link DeliveryOutcome}. The
 * {@code Idempotency-Key} header (= the notification / platform {@code event_id}) lets clients dedupe
 * the duplicates that at-least-once delivery may produce.
 */
@Component
public class WebClientWebhookAdapter implements WebhookClientPort {

    private final WebClient webClient;
    private final SsrfGuard ssrfGuard;

    public WebClientWebhookAdapter(WebhookProperties properties) {
        this.ssrfGuard = new SsrfGuard(properties.isBlockPrivateNetworks());
        HttpClient httpClient = HttpClient.create()
                .followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public Mono<DeliveryOutcome> post(WebhookRequest request) {
        if (!ssrfGuard.isAllowed(request.targetUrl())) {
            return Mono.just(DeliveryOutcome.failed(null, "blocked by SSRF guard: " + request.targetUrl(), 0));
        }
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return webClient.post()
                    .uri(request.targetUrl())
                    .header("Idempotency-Key", request.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request.payload())
                    .exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        long durationMs = elapsedMs(start);
                        DeliveryOutcome outcome = response.statusCode().is2xxSuccessful()
                                ? DeliveryOutcome.succeeded(status, durationMs)
                                : DeliveryOutcome.failed(status, "HTTP " + status, durationMs);
                        // Drain the body so the connection is released back to the pool.
                        return response.releaseBody().thenReturn(outcome);
                    })
                    .onErrorResume(ex -> Mono.just(DeliveryOutcome.failed(
                            null, ex.getClass().getSimpleName() + ": " + ex.getMessage(), elapsedMs(start))));
        });
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
