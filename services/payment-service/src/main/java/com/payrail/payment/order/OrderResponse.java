package com.payrail.payment.order;

import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        long amount,
        String currency,
        String receipt,
        OrderStatus status,
        Instant createdAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getAmountMinor(),
                order.getCurrency(),
                order.getReceipt(),
                order.getStatus(),
                order.getCreatedAt());
    }
}