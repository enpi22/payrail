package com.payrail.payment.payment.provider;

import com.payrail.payment.payment.PaymentMethod;

import java.util.UUID;

/** paymentId is our reference, so the provider can tell us about this payment later. */
public record ChargeRequest(UUID paymentId, long amountMinor, String currency, PaymentMethod method) {
}