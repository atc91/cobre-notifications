package com.cobre.notifications.delivery.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure-domain unit test of the backoff math, made deterministic by an injected jitter supplier. */
class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-10T12:00:00Z");

    /** base 30s, factor 2, cap 1h, fixed jitter of 5s so assertions are exact. */
    private static RetryPolicy withFixedJitter() {
        return new RetryPolicy(5, Duration.ofSeconds(30), 2, Duration.ofHours(1), () -> 5.0);
    }

    @Test
    void backoffGrowsExponentiallyPlusJitter() {
        RetryPolicy policy = withFixedJitter();
        // base * 2^attempts + 5s jitter
        assertThat(policy.nextRetryAt(0, NOW)).isEqualTo(NOW.plusSeconds(30 + 5));
        assertThat(policy.nextRetryAt(1, NOW)).isEqualTo(NOW.plusSeconds(60 + 5));
        assertThat(policy.nextRetryAt(2, NOW)).isEqualTo(NOW.plusSeconds(120 + 5));
    }

    @Test
    void delayIsClampedToCapForLargeAttemptCounts() {
        RetryPolicy policy = withFixedJitter();
        // 30 * 2^10 = 30720s, far past the 1h cap; jitter is inside the cap, so exactly 1h.
        assertThat(policy.nextRetryAt(10, NOW)).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void isExhaustedFlipsExactlyAtMaxAttempts() {
        RetryPolicy policy = withFixedJitter(); // maxAttempts = 5
        assertThat(policy.isExhausted(4)).isFalse();
        assertThat(policy.isExhausted(5)).isTrue();
        assertThat(policy.isExhausted(6)).isTrue();
    }

    @Test
    void defaultsUseA4Parameters() {
        RetryPolicy defaults = RetryPolicy.defaults();
        assertThat(defaults.maxAttempts()).isEqualTo(5);
        // first attempt is at least the 30s base (jitter is non-negative)
        assertThat(defaults.nextRetryAt(0, NOW)).isAfterOrEqualTo(NOW.plusSeconds(30));
    }
}
