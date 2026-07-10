package com.cobre.notifications.delivery.adapter.out;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF guard for client-supplied webhook URLs (OWASP A10). A pure collaborator of the webhook
 * adapter: no Spring, no I/O beyond host resolution, so its logic is exercised by a plain unit test.
 *
 * <p>When {@code blockPrivateNetworks} is on (production): the scheme must be {@code https}, and every
 * address the host resolves to must be public — loopback, link-local (including the
 * {@code 169.254.169.254} cloud-metadata address), private/site-local, wildcard, and multicast
 * addresses are rejected. Resolving and checking <em>all</em> addresses is the DNS-rebinding defence.
 *
 * <p>When off (test profile): {@code http} and loopback are permitted so a loopback stub server is
 * reachable, without resolving the host.
 */
public class SsrfGuard {

    private final boolean blockPrivateNetworks;

    public SsrfGuard(boolean blockPrivateNetworks) {
        this.blockPrivateNetworks = blockPrivateNetworks;
    }

    /** True when {@code url} is a safe delivery target under the current policy. */
    public boolean isAllowed(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || scheme == null) {
            return false;
        }
        if (!blockPrivateNetworks) {
            // Relaxed (test): permit http/https to any host, including the loopback stub. No resolution.
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        }
        if (!"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        try {
            InetAddress[] resolved = InetAddress.getAllByName(unbracket(host));
            if (resolved.length == 0) {
                return false;
            }
            for (InetAddress address : resolved) {
                if (isBlocked(address)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean isBlocked(InetAddress address) {
        return address.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || address.isLinkLocalAddress()  // 169.254.0.0/16 (incl. metadata), fe80::/10
                || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || address.isAnyLocalAddress()   // 0.0.0.0, ::
                || address.isMulticastAddress();
    }

    /** Strip the surrounding brackets from an IPv6 literal host so {@link InetAddress} accepts it. */
    private static String unbracket(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
