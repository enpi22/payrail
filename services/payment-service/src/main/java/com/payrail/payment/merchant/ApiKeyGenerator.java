package com.payrail.payment.merchant;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Creates API keys and webhook secrets.
 *
 * Key format: pr_test_<8 hex chars>_<43-char random secret>
 *             └──── prefix (16 chars) ────┘
 * The prefix is stored in plain text for lookup; only a SHA-256 hash of the full key is stored.
 */
@Component
public class ApiKeyGenerator {

    static final String KEY_PREFIX_START = "pr_test_";
    static final int PREFIX_LENGTH = KEY_PREFIX_START.length() + 8;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    public GeneratedKey generateApiKey() {
        String prefix = KEY_PREFIX_START + HexFormat.of().formatHex(randomBytes(4));
        String secret = BASE64_URL.encodeToString(randomBytes(32));
        String fullKey = prefix + "_" + secret;
        return new GeneratedKey(prefix, fullKey, sha256Hex(fullKey));
    }

    public String generateWebhookSecret() {
        return "whsec_" + BASE64_URL.encodeToString(randomBytes(32));
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public record GeneratedKey(String prefix, String fullKey, String hash) {
    }
}
