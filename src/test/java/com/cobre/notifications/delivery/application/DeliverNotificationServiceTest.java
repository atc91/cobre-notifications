package com.cobre.notifications.delivery.application;

import com.cobre.notifications.delivery.domain.model.DeliveryAttempt;
import com.cobre.notifications.delivery.domain.model.DeliveryOutcome;
import com.cobre.notifications.delivery.domain.model.DeliveryStatus;
import com.cobre.notifications.delivery.domain.model.Notification;
import com.cobre.notifications.delivery.domain.model.RetryPolicy;
import com.cobre.notifications.delivery.domain.model.WebhookRequest;
import com.cobre.notifications.delivery.domain.port.out.ClockPort;
import com.cobre.notifications.delivery.domain.port.out.NotificationStorePort;
import com.cobre.notifications.delivery.domain.port.out.WebhookClientPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the delivery use case (P-07/P-08). No Spring, no DB, no HTTP — the store, webhook, and
 * clock are mocked. It pins the <em>orchestration</em>: a claimed row is POSTed with the right request,
 * a 2xx drives {@code recordSuccess} and a non-2xx drives {@code recordFailure} against the retry
 * policy, and either way exactly one {@link DeliveryAttempt} is recorded. (The retry math and status
 * transitions themselves are already unit-tested in P-03.)
 */
class DeliverNotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    private final NotificationStorePort store = mock(NotificationStorePort.class);
    private final WebhookClientPort webhook = mock(WebhookClientPort.class);
    private final ClockPort clock = mock(ClockPort.class);
    // base 30s, factor 2, cap 1h, no jitter, 5 attempts — so nextRetryAt is exact.
    private final RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(30), 2, Duration.ofHours(1), () -> 0.0);
    private DeliverNotificationService service;

    @BeforeEach
    void setUp() {
        service = new DeliverNotificationService(
                store, webhook, clock, policy, new ObjectMapper(), new DeliveryProperties());
        when(clock.now()).thenReturn(NOW);
        when(store.recordOutcome(any(), any())).thenReturn(Mono.empty());
    }

    /** A notification as returned by claimDue: already DELIVERING with attempts incremented to 1. */
    private static Notification claimed() {
        Notification n = Notification.pending(
                "EVT001", "CLIENT001", "credit_card_payment", "Payment received",
                "https://client.example/webhook", NOW);
        n.claim(NOW); // PENDING -> DELIVERING, attempts = 1
        return n;
    }

    @Test
    void successRecordsDeliveredWithASuccessAttempt() {
        Notification n = claimed();
        when(store.claimDue(any(), anyInt())).thenReturn(Flux.just(n));
        when(webhook.post(any())).thenReturn(Mono.just(DeliveryOutcome.succeeded(200, 12)));

        StepVerifier.create(service.deliverDue()).verifyComplete();

        ArgumentCaptor<WebhookRequest> request = ArgumentCaptor.forClass(WebhookRequest.class);
        verify(webhook).post(request.capture());
        assertThat(request.getValue().idempotencyKey()).isEqualTo("EVT001");
        assertThat(request.getValue().targetUrl()).isEqualTo("https://client.example/webhook");

        ArgumentCaptor<DeliveryAttempt> attempt = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(store).recordOutcome(eq(n), attempt.capture());
        assertThat(n.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(attempt.getValue().notificationId()).isEqualTo("EVT001");
        assertThat(attempt.getValue().attemptNo()).isEqualTo(1);
        assertThat(attempt.getValue().httpStatus()).isEqualTo(200);
        assertThat(attempt.getValue().error()).isNull();
    }

    @Test
    void failureBelowMaxRecordsRetryingWithBackoff() {
        Notification n = claimed(); // attempts = 1, maxAttempts = 5
        when(store.claimDue(any(), anyInt())).thenReturn(Flux.just(n));
        when(webhook.post(any())).thenReturn(Mono.just(DeliveryOutcome.failed(500, "HTTP 500", 8)));

        StepVerifier.create(service.deliverDue()).verifyComplete();

        ArgumentCaptor<DeliveryAttempt> attempt = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(store).recordOutcome(eq(n), attempt.capture());
        assertThat(n.deliveryStatus()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(n.nextRetryAt()).isEqualTo(NOW.plusSeconds(60)); // 30 * 2^1, jitter 0
        assertThat(n.lastError()).isEqualTo("HTTP 500");
        assertThat(attempt.getValue().httpStatus()).isEqualTo(500);
        assertThat(attempt.getValue().error()).isEqualTo("HTTP 500");
    }
}
