package com.payrail.payment.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes committed outbox events to Kafka. At-least-once: a crash after sending but before
 * marking a row published means it is sent again, so consumers must deduplicate by event_id.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactionTemplate;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${payrail.outbox.poll-interval-ms:500}")
    public void publishPending() {
        transactionTemplate.executeWithoutResult(status -> {
            // SKIP LOCKED: if several app instances run the relay, each grabs different rows.
            List<PendingEvent> events = jdbc.query("""
                    SELECT id, aggregate_id, event_type, payload::text AS payload
                    FROM outbox_events
                    WHERE published_at IS NULL
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """,
                    (rs, i) -> new PendingEvent(
                            rs.getObject("id", UUID.class),
                            rs.getObject("aggregate_id", UUID.class),
                            rs.getString("event_type"),
                            rs.getString("payload")),
                    BATCH_SIZE);

            for (PendingEvent event : events) {
                try {
                    // Key = payment id: all events for one payment land in one partition, in order.
                    kafka.send(KafkaTopicsConfig.PAYMENT_EVENTS_TOPIC, event.aggregateId().toString(), event.payload())
                            .get(10, TimeUnit.SECONDS);
                    jdbc.update("UPDATE outbox_events SET published_at = now() WHERE id = ?", event.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    // Stop here; this and later events stay unpublished and are retried next tick, in order.
                    log.warn("Outbox publish failed, will retry eventId={} type={}", event.id(), event.eventType(), e);
                    return;
                }
            }
        });
    }

    private record PendingEvent(UUID id, UUID aggregateId, String eventType, String payload) {
    }
}