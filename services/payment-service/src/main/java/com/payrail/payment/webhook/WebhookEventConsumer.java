package com.payrail.payment.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.payment.outbox.KafkaTopicsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Turns payment events into pending webhook deliveries. It only records the job; it never calls merchants,
 * so one merchant's slow or broken server can't block the partition for everyone else.
 */
@Component
public class WebhookEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventConsumer.class);

    private final WebhookDeliveryStore store;
    private final ObjectMapper objectMapper;

    public WebhookEventConsumer(WebhookDeliveryStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopicsConfig.PAYMENT_EVENTS_TOPIC, groupId = "webhook-dispatcher")
    public void onPaymentEvent(String payload) {
        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new MalformedEventException("Event is not valid JSON", e);
        }

        UUID eventId = requireUuid(event, "event_id");
        UUID merchantId = requireUuid(event, "merchant_id");
        String type = event.path("type").asText(null);
        if (type == null) {
            throw new MalformedEventException("Event has no type");
        }

        boolean created = store.enqueue(eventId, merchantId, type, payload);
        log.debug("Webhook {} eventId={} type={}", created ? "enqueued" : "skipped (duplicate or no URL)", eventId, type);
    }

    private static UUID requireUuid(JsonNode event, String field) {
        try {
            return UUID.fromString(event.path(field).asText());
        } catch (IllegalArgumentException e) {
            throw new MalformedEventException("Event has no valid " + field, e);
        }
    }
}