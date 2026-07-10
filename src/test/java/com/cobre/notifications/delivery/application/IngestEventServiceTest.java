package com.cobre.notifications.delivery.application;

import com.cobre.notifications.delivery.domain.model.DeliveryStatus;
import com.cobre.notifications.delivery.domain.model.Notification;
import com.cobre.notifications.delivery.domain.model.PlatformEvent;
import com.cobre.notifications.delivery.domain.model.SubscriptionLookup;
import com.cobre.notifications.delivery.domain.port.out.ClockPort;
import com.cobre.notifications.delivery.domain.port.out.NotificationStorePort;
import com.cobre.notifications.delivery.domain.port.out.SubscriptionPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the subscription-gated ingest use case. No Spring, no database — the subscription port,
 * store, and clock are mocked, so this pins down the gate decision in isolation: a resolved subscription
 * persists a {@code PENDING} notification carrying the resolved {@code targetUrl}; an unresolved one is
 * skipped with no write (no cross-client leakage).
 */
class IngestEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    private final SubscriptionPort subscriptions = mock(SubscriptionPort.class);
    private final NotificationStorePort store = mock(NotificationStorePort.class);
    private final ClockPort clock = mock(ClockPort.class);
    private final IngestEventService service = new IngestEventService(subscriptions, store, clock);

    @Test
    void subscribedEventPersistsAPendingNotificationWithResolvedTargetUrl() {
        when(clock.now()).thenReturn(NOW);
        when(subscriptions.resolve("CLIENT001", "credit_card_payment"))
                .thenReturn(Mono.just(new SubscriptionLookup("https://client1.example/webhook", "whsec_1")));
        when(store.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        PlatformEvent event = new PlatformEvent(
                "EVT001", "credit_card_payment", "Credit card payment received for $150.00",
                "CLIENT001", Instant.parse("2024-03-15T09:30:22Z"));

        StepVerifier.create(service.ingest(event)).verifyComplete();

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(store, times(1)).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.id()).isEqualTo("EVT001");
        assertThat(saved.clientId()).isEqualTo("CLIENT001");
        assertThat(saved.eventType()).isEqualTo("credit_card_payment");
        assertThat(saved.content()).isEqualTo("Credit card payment received for $150.00");
        assertThat(saved.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(saved.attempts()).isZero();
        assertThat(saved.targetUrl()).isEqualTo("https://client1.example/webhook"); // resolved by the gate
        assertThat(saved.createdAt()).isEqualTo(NOW);
        assertThat(saved.updatedAt()).isEqualTo(NOW);
        assertThat(saved.nextRetryAt()).isNull();
    }

    @Test
    void unsubscribedEventIsSkippedWithoutPersisting() {
        // No active subscription for this client/type: resolve returns empty. The event must not be
        // persisted (no cross-client leakage), and ingest still completes normally (skip, not error).
        when(subscriptions.resolve(any(), any())).thenReturn(Mono.empty());

        PlatformEvent event = new PlatformEvent(
                "EVT009", "credit_cashback", "Cashback reward credited for $25.00", "CLIENT003", NOW);

        StepVerifier.create(service.ingest(event)).verifyComplete();

        verify(store, never()).save(any());
    }
}
