package com.payrail.payment.outbox;

import java.time.Instant;
import java.util.UUID;

/** The JSON every event shares. eventId lets consumers drop duplicates (delivery is at-least-once). */
public record OutboxEnvelope(UUID eventId, String type, Instant createdAt, UUID merchantId, Object data) {
}