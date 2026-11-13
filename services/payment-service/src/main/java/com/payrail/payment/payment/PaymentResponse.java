package com.payrail.payment.payment;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        long amount,
        String currency,
        PaymentMethod method,
        PaymentStatus status,
        String provider,
        String failureCode,
        Instant createdAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                payment.getMethod(),
                payment.getStatus(),
                payment.getProvider(),
                payment.getFailureCode(),
                payment.getCreatedAt());
    }
}