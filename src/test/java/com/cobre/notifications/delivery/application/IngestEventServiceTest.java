package com.cobre.notifications.delivery.application;

import com.cobre.notifications.delivery.domain.model.DeliveryStatus;
import com.cobre.notifications.delivery.domain.model.Notification;
import com.cobre.notifications.delivery.domain.model.PlatformEvent;
import com.cobre.notifications.delivery.domain.port.out.ClockPort;
import com.cobre.notifications.delivery.domain.port.out.NotificationStorePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the ingest use case. No Spring, no database — the store and clock are mocked, so this
 * pins down the mapping {@code PlatformEvent → PENDING Notification} and the single delegation to the
 * store in isolation.
 */
class IngestEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    private final NotificationStorePort store = mock(NotificationStorePort.class);
    private final ClockPort clock = mock(ClockPort.class);
    private final IngestEventService service = new IngestEventService(store, clock);

    @Test
    void ingestPersistsAPendingNotificationStampedWithTheClock() {
        when(clock.now()).thenReturn(NOW);
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
        assertThat(saved.targetUrl()).isNull();          // resolved by the subscription gate in P-05
        assertThat(saved.createdAt()).isEqualTo(NOW);
        assertThat(saved.updatedAt()).isEqualTo(NOW);
        assertThat(saved.nextRetryAt()).isNull();
    }

    @Test
    void ingestInvokesTheStoreExactlyOnce() {
        when(clock.now()).thenReturn(NOW);
        when(store.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        PlatformEvent event = new PlatformEvent(
                "EVT002", "debit_card_withdrawal", "ATM withdrawal of $200.00", "CLIENT001", NOW);

        StepVerifier.create(service.ingest(event)).verifyComplete();

        verify(store, times(1)).save(any());
    }
}
