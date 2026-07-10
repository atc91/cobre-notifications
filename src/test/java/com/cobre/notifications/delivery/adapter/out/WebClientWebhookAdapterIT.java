package com.cobre.notifications.delivery.adapter.out;

import com.cobre.notifications.delivery.domain.model.WebhookRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the WebClient webhook adapter (P-06) against an OkHttp {@link MockWebServer} on
 * the loopback interface. The adapter is constructed directly (no Spring) with the guard relaxed so the
 * stub is reachable — except the last case, which keeps the guard strict to prove an SSRF-blocked target
 * is rejected with no network call. Verifies the outcome mapping, the request headers, timeout handling,
 * and that redirects are not followed.
 */
class WebClientWebhookAdapterIT {

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private WebClientWebhookAdapter adapter(boolean blockPrivateNetworks, long readTimeoutMs) {
        WebhookProperties props = new WebhookProperties();
        props.setBlockPrivateNetworks(blockPrivateNetworks);
        props.setReadTimeoutMs(readTimeoutMs);
        props.setConnectTimeoutMs(2000);
        return new WebClientWebhookAdapter(props);
    }

    private WebhookRequest requestTo(String path) {
        return new WebhookRequest(server.url(path).toString(), "{\"event_id\":\"EVT001\"}", "EVT001", null);
    }

    @Test
    void twoxxYieldsSuccessAndSendsIdempotencyAndJsonHeaders() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200));

        StepVerifier.create(adapter(false, 5000).post(requestTo("/hook")))
                .assertNext(outcome -> {
                    assertThat(outcome.success()).isTrue();
                    assertThat(outcome.httpStatus()).isEqualTo(200);
                })
                .verifyComplete();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("Idempotency-Key")).isEqualTo("EVT001");
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json");
    }

    @Test
    void nonTwoxxYieldsFailureAndNeverThrows() {
        server.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(adapter(false, 5000).post(requestTo("/hook")))
                .assertNext(outcome -> {
                    assertThat(outcome.success()).isFalse();
                    assertThat(outcome.httpStatus()).isEqualTo(500);
                })
                .verifyComplete();
    }

    @Test
    void readTimeoutYieldsFailureWithNullStatus() {
        // Delay the response headers past the 200ms read timeout so the call times out.
        server.enqueue(new MockResponse().setResponseCode(200).setHeadersDelay(2, TimeUnit.SECONDS));

        StepVerifier.create(adapter(false, 200).post(requestTo("/hook")))
                .assertNext(outcome -> {
                    assertThat(outcome.success()).isFalse();
                    assertThat(outcome.httpStatus()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void redirectIsNotFollowedAndSurfacesAsNonTwoxx() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"));

        StepVerifier.create(adapter(false, 5000).post(requestTo("/hook")))
                .assertNext(outcome -> {
                    assertThat(outcome.success()).isFalse();
                    assertThat(outcome.httpStatus()).isEqualTo(302);
                })
                .verifyComplete();

        assertThat(server.getRequestCount()).isEqualTo(1); // did not follow the redirect
    }

    @Test
    void ssrfBlockedTargetYieldsFailureWithNoNetworkCall() {
        WebhookRequest blocked = new WebhookRequest(
                "https://169.254.169.254/latest/meta-data", "{}", "EVT001", null);

        StepVerifier.create(adapter(true, 5000).post(blocked)) // guard ON
                .assertNext(outcome -> {
                    assertThat(outcome.success()).isFalse();
                    assertThat(outcome.error()).contains("SSRF");
                })
                .verifyComplete();

        assertThat(server.getRequestCount()).isZero();
    }
}
