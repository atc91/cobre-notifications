package com.cobre.notifications.delivery.adapter.out;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test of the SSRF guard (P-06). No Spring, no network: every case uses an IP-literal URL so
 * {@code InetAddress} resolves offline. Pins down the https-only + private/loopback/metadata blocking
 * that stops a client-supplied webhook URL from reaching Cobre's internal network.
 */
class SsrfGuardTest {

    private final SsrfGuard strict = new SsrfGuard(true);
    private final SsrfGuard relaxed = new SsrfGuard(false);

    @Test
    void allowsPublicHttpsTarget() {
        // 93.184.216.34 is a public address (example.com); as an IP literal it resolves without DNS.
        assertThat(strict.isAllowed("https://93.184.216.34/webhook")).isTrue();
    }

    @Test
    void blocksNonHttpsScheme() {
        assertThat(strict.isAllowed("http://93.184.216.34/webhook")).isFalse();
    }

    @Test
    void blocksLoopback() {
        assertThat(strict.isAllowed("https://127.0.0.1/webhook")).isFalse();
        assertThat(strict.isAllowed("https://[::1]/webhook")).isFalse();
    }

    @Test
    void blocksPrivateRanges() {
        assertThat(strict.isAllowed("https://10.0.0.5/webhook")).isFalse();
        assertThat(strict.isAllowed("https://192.168.1.10/webhook")).isFalse();
        assertThat(strict.isAllowed("https://172.16.0.1/webhook")).isFalse();
    }

    @Test
    void blocksLinkLocalMetadataAddress() {
        assertThat(strict.isAllowed("https://169.254.169.254/latest/meta-data")).isFalse();
    }

    @Test
    void blocksWildcardMulticastAndMalformed() {
        assertThat(strict.isAllowed("https://0.0.0.0/webhook")).isFalse();
        assertThat(strict.isAllowed("https://224.0.0.1/webhook")).isFalse(); // multicast
        assertThat(strict.isAllowed("https:///no-host")).isFalse();
        assertThat(strict.isAllowed("not a url")).isFalse();
    }

    @Test
    void relaxedGuardPermitsHttpLoopbackForTheStub() {
        assertThat(relaxed.isAllowed("http://127.0.0.1:8080/webhook")).isTrue();
        assertThat(relaxed.isAllowed("https://93.184.216.34/webhook")).isTrue();
    }
}
