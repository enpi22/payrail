package com.payrail.payment.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF protection: merchant-supplied URLs must never make our servers call our own private network
 * (internal services, the database, the cloud metadata endpoint at 169.254.169.254).
 *
 * Known limitation: DNS could change between this check and the connection (DNS rebinding).
 * The complete fix is an egress proxy enforcing the same rules.
 */
@Component
public class WebhookTargetGuard {

    private final boolean allowLocalTargets;

    public WebhookTargetGuard(@Value("${payrail.webhooks.allow-local-targets:false}") boolean allowLocalTargets) {
        this.allowLocalTargets = allowLocalTargets;
    }

    public void check(URI uri) throws UnsafeTargetException, UnknownHostException {
        String host = uri.getHost();
        if (host == null) {
            throw new UnsafeTargetException("Webhook URL has no host");
        }
        if (allowLocalTargets) {
            return; // local development and tests only
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new UnsafeTargetException("Webhook URL must use https");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (isNonPublic(address)) {
                throw new UnsafeTargetException("Webhook URL resolves to a non-public address");
            }
        }
    }

    static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64; // 100.64.0.0/10, carrier-grade NAT
        }
        return (bytes[0] & 0xfe) == 0xfc; // fc00::/7, IPv6 unique local addresses
    }

    public static class UnsafeTargetException extends Exception {
        public UnsafeTargetException(String message) {
            super(message);
        }
    }
}