package com.payrail.payment.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    private static final String SECRET = "whsec_test";
    private static final String BODY = "{\"event_id\":\"e1\"}";
    private static final long NOW = 1_700_000_000L;
    private static final long TOLERANCE = 300; // 5 minutes

    @Test
    void matchesAKnownTestVector() {
        // Computed independently: HMAC-SHA256(key="whsec_test", data="1700000000.{\"event_id\":\"e1\"}")
        assertThat(WebhookSigner.sign(SECRET, NOW, BODY))
                .isEqualTo("t=1700000000,v1=8a9b910184f1d9ed3590e106c991e230a182f3b9356c7c1ed91c013d17ed0775");
    }

    @Test
    void freshSignatureVerifies() {
        String header = WebhookSigner.sign(SECRET, NOW, BODY);
        assertThat(WebhookSigner.verify(SECRET, header, BODY, NOW + 10, TOLERANCE)).isTrue();
    }

    @Test
    void tamperedBodyIsRejected() {
        String header = WebhookSigner.sign(SECRET, NOW, BODY);
        assertThat(WebhookSigner.verify(SECRET, header, "{\"event_id\":\"e2\"}", NOW, TOLERANCE)).isFalse();
    }

    @Test
    void wrongSecretIsRejected() {
        String header = WebhookSigner.sign("whsec_attacker", NOW, BODY);
        assertThat(WebhookSigner.verify(SECRET, header, BODY, NOW, TOLERANCE)).isFalse();
    }

    @Test
    void oldSignatureIsRejectedToPreventReplay() {
        String header = WebhookSigner.sign(SECRET, NOW, BODY);
        assertThat(WebhookSigner.verify(SECRET, header, BODY, NOW + 301, TOLERANCE)).isFalse();
    }

    @Test
    void changingTheTimestampBreaksTheSignature() {
        // An attacker can't refresh an old webhook by editing t, because t is part of what was signed.
        String header = WebhookSigner.sign(SECRET, NOW, BODY).replace("t=" + NOW, "t=" + (NOW + 600));
        assertThat(WebhookSigner.verify(SECRET, header, BODY, NOW + 600, TOLERANCE)).isFalse();
    }

    @Test
    void malformedHeadersAreRejected() {
        assertThat(WebhookSigner.verify(SECRET, null, BODY, NOW, TOLERANCE)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, "garbage", BODY, NOW, TOLERANCE)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, "t=abc,v1=00", BODY, NOW, TOLERANCE)).isFalse();
        assertThat(WebhookSigner.verify(SECRET, "v1=00", BODY, NOW, TOLERANCE)).isFalse();
    }
}