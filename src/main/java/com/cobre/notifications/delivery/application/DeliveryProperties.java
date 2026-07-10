package com.cobre.notifications.delivery.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the delivery worker, bound from {@code notifications.delivery.*}. Lives in the
 * application layer because it configures the use case; it imports no adapter, web, or R2DBC types.
 * The scheduler reads {@code poll-interval-ms} directly via its {@code @Scheduled} expression.
 */
@ConfigurationProperties("notifications.delivery")
public class DeliveryProperties {

    /** Maximum notifications claimed per delivery poll. */
    private int batchSize = 100;

    /** Delay between delivery polls, in milliseconds (used by the scheduler's fixed-delay). */
    private long pollIntervalMs = 5000;

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }
}
