package com.payrail.payment.auth;

import java.util.UUID;

/** Who is making the current request. Available to any controller via @AuthenticationPrincipal. */
public record AuthenticatedMerchant(UUID merchantId, String keyPrefix) {
}