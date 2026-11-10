package com.payrail.payment.merchant;

import java.util.UUID;

/** Returned once at signup. The API key is never retrievable again. */
public record MerchantCreatedResponse(
        UUID merchantId,
        String name,
        String apiKey,
        String webhookSecret
) {
}
