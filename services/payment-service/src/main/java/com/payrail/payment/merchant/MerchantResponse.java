package com.payrail.payment.merchant;

import java.time.Instant;
import java.util.UUID;

/** Public view of a merchant. Never includes secrets. */
public record MerchantResponse(
        UUID merchantId,
        String name,
        String webhookUrl,
        Instant createdAt
) {

}