package com.payrail.payment.payment;

import com.payrail.payment.common.ApiException;
import com.payrail.payment.payment.provider.MockPaymentProvider;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Where providers report final outcomes. Not authenticated by API key (providers aren't merchants),
 * so every call must prove it comes from the provider. Real providers sign the payload with HMAC;
 * the mock uses a shared secret.
 */
@RestController
@RequestMapping("/v1/provider-callbacks")
public class ProviderCallbackController {

    private final PaymentService paymentService;
    private final byte[] mockSecret;

    public ProviderCallbackController(PaymentService paymentService,
                                      @Value("${payrail.providers.mock.callback-secret}") String mockSecret) {
        this.paymentService = paymentService;
        this.mockSecret = mockSecret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@PathVariable String provider,
                        @RequestHeader(value = "X-Provider-Secret", required = false) String secret,
                        @Valid @RequestBody ProviderCallback callback) {
        if (!MockPaymentProvider.NAME.equals(provider)) {
            throw ApiException.notFound("Unknown provider");
        }
        if (secret == null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), mockSecret)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_provider_credentials",
                    "Invalid provider credentials");
        }
        paymentService.handleCallback(provider, callback);
    }
}