package com.cobre.notifications.delivery.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retry-policy configuration, bound from {@code notifications.retry.*}. Defaults are assumption A4
 * (5 attempts, 30s base, ×2, 1h cap). Exposed as config so integration tests can dial the delay down
 * to zero and drive retry sequences without real-time waits.
 */
@ConfigurationProperties("notifications.retry")
public class RetryProperties {

    /** Maximum delivery attempts before dead-lettering to FAILED. */
    private int maxAttempts = 5;

    /** Base back-off delay in milliseconds. */
    private long baseDelayMs = 30_000;

    /** Exponential growth factor applied per attempt. */
    private int factor = 2;

    /** Upper bound on any single back-off delay, in milliseconds. */
    private long capMs = 3_600_000;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getBaseDelayMs() {
        return baseDelayMs;
    }

    public void setBaseDelayMs(long baseDelayMs) {
        this.baseDelayMs = baseDelayMs;
    }

    public int getFactor() {
        return factor;
    }

    public void setFactor(int factor) {
        this.factor = factor;
    }

    public long getCapMs() {
        return capMs;
    }

    public void setCapMs(long capMs) {
        this.capMs = capMs;
    }
}
