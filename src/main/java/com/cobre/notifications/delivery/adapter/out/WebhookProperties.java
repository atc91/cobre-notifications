package com.cobre.notifications.delivery.adapter.out;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the webhook client adapter, bound from {@code notifications.webhook.*}.
 *
 * <p>{@link #blockPrivateNetworks} is the SSRF-guard master switch: {@code true} in production
 * (https-only, private/loopback/metadata ranges rejected), set {@code false} in the test profile so
 * the loopback stub server is reachable.
 */
@ConfigurationProperties("notifications.webhook")
public class WebhookProperties {

    /** When true, the SSRF guard enforces https-only and rejects private/loopback/metadata targets. */
    private boolean blockPrivateNetworks = true;

    /** TCP connect timeout for the webhook call, in milliseconds. */
    private long connectTimeoutMs = 2000;

    /** Response (read) timeout for the webhook call, in milliseconds. */
    private long readTimeoutMs = 5000;

    public boolean isBlockPrivateNetworks() {
        return blockPrivateNetworks;
    }

    public void setBlockPrivateNetworks(boolean blockPrivateNetworks) {
        this.blockPrivateNetworks = blockPrivateNetworks;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
