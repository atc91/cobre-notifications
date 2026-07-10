package com.cobre.notifications.delivery.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure-domain unit test of the {@link Notification} aggregate's lifecycle behaviour. No Spring. */
class NotificationTest {

    private static final Instant T0 = Instant.parse("2026-07-10T12:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);

    /** A policy with no jitter and 5 attempts, so {@code nextRetryAt} is exact and reproducible. */
    private static RetryPolicy policyMaxAttempts(int max) {
        return new RetryPolicy(max, Duration.ofSeconds(30), 2, Duration.ofHours(1), () -> 0.0);
    }

    private static Notification pending() {
        return Notification.pending("EVT001", "CLIENT001", "credit_card_payment",
                "Payment received", "https://client.example/webhook", T0);
    }

    @Test
    void pendingFactoryStartsInPendingWithZeroAttempts() {
        Notification n = pending();
        assertThat(n.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(n.attempts()).isZero();
        assertThat(n.createdAt()).isEqualTo(T0);
    }

    @Test
    void claimFromPendingMovesToDeliveringAndIncrementsAttempts() {
        Notification n = pending();
        n.claim(T1);
        assertThat(n.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERING);
        assertThat(n.attempts()).isEqualTo(1);
        assertThat(n.claimedAt()).isEqualTo(T1);
        assertThat(n.updatedAt()).isEqualTo(T1);
    }

    @Test
    void claimFromRetryingMovesToDelivering() {
        Notification n = pending();
        n.claim(T0);
        n.recordFailure(policyMaxAttempts(5), T0, "boom"); // -> RETRYING
        n.claim(T1); // RETRYING -> DELIVERING
        assertThat(n.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERING);
        assertThat(n.attempts()).isEqualTo(2);
    }

    @Test
    void recordSuccessFromDeliveringMovesToDeliveredAndSetsDeliveredAt() {
        Notification n = pending();
        n.claim(T0);
        n.recordSuccess(T1);
        assertThat(n.deliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(n.deliveredAt()).isEqualTo(T1);
        assertThat(n.nextRetryAt()).isNull();
    }

    @Test
    void recordFailureBelowMaxSchedulesRetry() {
        RetryPolicy policy = policyMaxAttempts(5);
        Notification n = pending();
        n.claim(T0); // attempts = 1
        n.recordFailure(policy, T0, "timeout");
        assertThat(n.deliveryStatus()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(n.lastError()).isEqualTo("timeout");
        // base * 2^attempts = 30 * 2^1 = 60s, jitter 0
        assertThat(n.nextRetryAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void recordFailureAtMaxDeadLettersToFailed() {
        RetryPolicy policy = policyMaxAttempts(1);
        Notification n = pending();
        n.claim(T0); // attempts = 1, which equals maxAttempts
        n.recordFailure(policy, T0, "gave up");
        assertThat(n.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(n.nextRetryAt()).isNull();
        assertThat(n.lastError()).isEqualTo("gave up");
    }

    @Test
    void replayFromFailedResetsToPendingDueNow() {
        RetryPolicy policy = policyMaxAttempts(1);
        Notification n = pending();
        n.claim(T0);
        n.recordFailure(policy, T0, "gave up"); // -> FAILED
        n.replay(T1);
        assertThat(n.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(n.nextRetryAt()).isEqualTo(T1);
        assertThat(n.claimedAt()).isNull();
        assertThat(n.lastError()).isNull();
    }

    @Test
    void illegalTransitionsThrow() {
        assertThatThrownBy(() -> pending().recordSuccess(T0))
                .isInstanceOf(IllegalStatusTransitionException.class);

        Notification delivered = pending();
        delivered.claim(T0);
        delivered.recordSuccess(T1);
        assertThatThrownBy(() -> delivered.replay(T1))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }
}
