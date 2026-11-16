-- One row per webhook that must reach a merchant. The consumer creates rows; the sender works through them.
CREATE TABLE webhook_deliveries (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id         UUID NOT NULL,             -- the outbox event id: the deduplication key
    merchant_id      UUID NOT NULL REFERENCES merchants(id),
    event_type       TEXT NOT NULL,
    payload          JSONB NOT NULL,            -- the exact event JSON we send (and sign)
    status           TEXT NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD')),
    attempts         INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_status_code INT,                       -- the merchant's last HTTP status, if we got one
    last_error       TEXT,                      -- what went wrong last time, for debugging
    delivered_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The same Kafka event can arrive twice (at-least-once); it must never create two deliveries.
    CONSTRAINT uq_webhook_deliveries_event UNIQUE (event_id)
);

-- The sender's question every second: "which pending deliveries are due?"
-- Partial: delivered and dead rows (the vast majority over time) aren't in this index at all.
CREATE INDEX idx_webhook_deliveries_due
    ON webhook_deliveries (next_attempt_at)
    WHERE status = 'PENDING';

-- For a future "show my webhook history" view.
CREATE INDEX idx_webhook_deliveries_merchant
    ON webhook_deliveries (merchant_id, created_at DESC);