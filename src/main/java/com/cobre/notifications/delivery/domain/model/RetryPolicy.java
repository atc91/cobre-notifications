package com.cobre.notifications.delivery.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * The exponential-backoff retry policy (assumption A4): {@code maxAttempts = 5}, {@code baseDelay =
 * 30s}, {@code factor = 2}, {@code cap = 1h}. Given the current attempt count it computes the next
 * retry instant as {@code now + min(baseDelay·factor^attempts + jitter, cap)}.
 *
 * <p>Jitter is supplied by an injected {@link DoubleSupplier} (seconds), so production spreads load
 * with real randomness while tests inject a fixed value and assert an exact {@code nextRetryAt}.
 * This keeps the backoff math a pure, deterministic unit test.
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final int factor;
    private final Duration cap;
    private final DoubleSupplier jitterSeconds;

    public RetryPolicy(int maxAttempts, Duration baseDelay, int factor, Duration cap,
                       DoubleSupplier jitterSeconds) {
        this.maxAttempts = maxAttempts;
        this.baseDelay = Objects.requireNonNull(baseDelay, "baseDelay");
        this.factor = factor;
        this.cap = Objects.requireNonNull(cap, "cap");
        this.jitterSeconds = Objects.requireNonNull(jitterSeconds, "jitterSeconds");
    }

    /** The production policy with A4 defaults and a random jitter of [0, baseDelay) seconds. */
    public static RetryPolicy defaults() {
        Duration base = Duration.ofSeconds(30);
        return new RetryPolicy(5, base, 2, Duration.ofHours(1),
                () -> ThreadLocalRandom.current().nextDouble(base.toSeconds()));
    }

    /**
     * When to next attempt delivery, given how many attempts have already been made:
     * {@code now + min(baseDelay·factor^attempts + jitter, cap)}. Jitter is inside the cap, so the
     * delay never exceeds {@code cap}. The exponent is computed in {@code double} and clamped to
     * {@code cap} before narrowing, so a large attempt count cannot overflow.
     */
    public Instant nextRetryAt(int attempts, Instant now) {
        double backoffMillis = baseDelay.toMillis() * Math.pow(factor, attempts);
        double jitterMillis = jitterSeconds.getAsDouble() * 1000;
        long delayMillis = (long) Math.min(backoffMillis + jitterMillis, (double) cap.toMillis());
        return now.plusMillis(delayMillis);
    }

    /** True once no attempts remain and the notification must dead-letter to {@code FAILED}. */
    public boolean isExhausted(int attempts) {
        return attempts >= maxAttempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
