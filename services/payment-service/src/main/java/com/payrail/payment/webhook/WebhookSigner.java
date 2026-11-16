package com.payrail.payment.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Stripe-style webhook signatures.
 *
 * Header: Payrail-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body" keyed with the merchant's secret>
 *
 * Signing the timestamp together with the body means a captured webhook can't be replayed later:
 * merchants reject signatures older than a few minutes.
 */
public final class WebhookSigner {

    public static final String SIGNATURE_HEADER = "Payrail-Signature";

    private WebhookSigner() {
    }

    public static String sign(String secret, long timestampSeconds, String body) {
        return "t=" + timestampSeconds + ",v1=" + hmacSha256Hex(secret, timestampSeconds + "." + body);
    }

    /**
     * What a merchant does to check a webhook. Returns false for a wrong signature, a tampered body,
     * a malformed header, or a timestamp outside the tolerance window.
     */
    public static boolean verify(String secret, String header, String body, long nowSeconds, long toleranceSeconds) {
        if (header == null) {
            return false;
        }
        Long timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) {
                return false;
            }
            switch (pair[0].trim()) {
                case "t" -> {
                    try {
                        timestamp = Long.parseLong(pair[1].trim());
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }
                case "v1" -> signature = pair[1].trim();
                default -> { /* ignore unknown parts, so new signature versions can be added later */ }
            }
        }
        if (timestamp == null || signature == null) {
            return false;
        }
        if (Math.abs(nowSeconds - timestamp) > toleranceSeconds) {
            return false; // too old (or from the future): possible replay
        }
        String expected = hmacSha256Hex(secret, timestamp + "." + body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}