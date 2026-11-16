package com.payrail.payment.webhook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** All SQL for webhook_deliveries. Every write is a single statement, so each is atomic on its own. */
@Repository
public class WebhookDeliveryStore {

    private static final int MAX_ERROR_LENGTH = 500;

    private final JdbcTemplate jdbc;

    public WebhookDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates a pending delivery, unless the merchant has no webhook URL or this event was already enqueued.
     * Returns true if a row was created.
     */
    public boolean enqueue(UUID eventId, UUID merchantId, String eventType, String payload) {
        return jdbc.update("""
                INSERT INTO webhook_deliveries (event_id, merchant_id, event_type, payload)
                SELECT ?, m.id, ?, ?::jsonb
                FROM merchants m
                WHERE m.id = ? AND m.webhook_url IS NOT NULL
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, eventType, payload, merchantId) == 1;
    }

    /**
     * Claims due deliveries with a lease: attempts goes up and next_attempt_at moves into the future,
     * so no other sender picks them while they're in flight. If this sender crashes mid-send, the lease
     * simply expires and the delivery is retried (at-least-once).
     */
    public List<ClaimedDelivery> claimDue(int batchSize, int leaseSeconds) {
        return jdbc.query("""
                UPDATE webhook_deliveries d
                SET attempts = d.attempts + 1,
                    next_attempt_at = now() + (? * interval '1 second')
                FROM merchants m
                WHERE m.id = d.merchant_id
                  AND d.id IN (
                      SELECT id FROM webhook_deliveries
                      WHERE status = 'PENDING' AND next_attempt_at <= now()
                      ORDER BY next_attempt_at
                      LIMIT ?
                      FOR UPDATE SKIP LOCKED)
                RETURNING d.id, d.event_id, d.event_type, d.payload::text AS payload, d.attempts,
                          m.webhook_url, m.webhook_secret
                """,
                (rs, i) -> new ClaimedDelivery(
                        rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getInt("attempts"),
                        rs.getString("webhook_url"),
                        rs.getString("webhook_secret")),
                leaseSeconds, batchSize);
    }

    public void markDelivered(UUID id, int statusCode) {
        jdbc.update("""
                UPDATE webhook_deliveries
                SET status = 'DELIVERED', delivered_at = now(), last_status_code = ?, last_error = NULL
                WHERE id = ? AND status = 'PENDING'
                """, statusCode, id);
    }

    public void scheduleRetry(UUID id, Integer statusCode, String error, long delayMillis) {
        jdbc.update("""
                UPDATE webhook_deliveries
                SET next_attempt_at = now() + (? * interval '1 millisecond'), last_status_code = ?, last_error = ?
                WHERE id = ? AND status = 'PENDING'
                """, delayMillis, statusCode, truncate(error), id);
    }

    public void markDead(UUID id, Integer statusCode, String error) {
        jdbc.update("""
                UPDATE webhook_deliveries
                SET status = 'DEAD', last_status_code = ?, last_error = ?
                WHERE id = ? AND status = 'PENDING'
                """, statusCode, truncate(error), id);
    }

    private static String truncate(String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    public record ClaimedDelivery(UUID id, UUID eventId, String eventType, String payload, int attempts,
                                  String webhookUrl, String webhookSecret) {
    }
}