-- Payrail payment-service schema (Flyway V1)
-- All money is stored as BIGINT in minor units (paise). Never floats. See ADR 0001.

-- ───────────── Merchants & authentication ─────────────
CREATE TABLE merchants (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           TEXT NOT NULL,
    webhook_url    TEXT,
    webhook_secret TEXT NOT NULL,              -- used to sign webhook payloads (HMAC-SHA256)
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- API keys are shown to the merchant once; only a hash is stored.
CREATE TABLE api_keys (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    key_prefix  TEXT NOT NULL UNIQUE,          -- e.g. "pr_test_ab12", used for lookup
    key_hash    TEXT NOT NULL,                 -- SHA-256 of the full key
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ
);

-- ───────────── Orders & payments ─────────────
CREATE TABLE orders (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id  UUID NOT NULL REFERENCES merchants(id),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency     CHAR(3) NOT NULL DEFAULT 'INR',
    receipt      TEXT NOT NULL,                -- merchant's own reference
    status       TEXT NOT NULL DEFAULT 'CREATED'
                 CHECK (status IN ('CREATED', 'ATTEMPTED', 'PAID')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (merchant_id, receipt)
);

CREATE TABLE payments (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     UUID NOT NULL REFERENCES orders(id),
    merchant_id  UUID NOT NULL REFERENCES merchants(id),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency     CHAR(3) NOT NULL,
    method       TEXT NOT NULL CHECK (method IN ('UPI', 'CARD', 'NETBANKING')),
    -- State machine: CREATED -> PROCESSING -> SUCCEEDED | FAILED  (REFUNDED added later)
    status       TEXT NOT NULL DEFAULT 'CREATED'
                 CHECK (status IN ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    provider     TEXT,                         -- which provider the router chose
    provider_ref TEXT,                         -- provider's transaction id
    failure_code TEXT,
    version      INT NOT NULL DEFAULT 0,       -- optimistic locking for status transitions
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_order ON payments (order_id);
CREATE INDEX idx_payments_merchant_created ON payments (merchant_id, created_at DESC);
-- A provider must never report the same transaction twice.
CREATE UNIQUE INDEX uq_payments_provider_ref ON payments (provider, provider_ref)
    WHERE provider_ref IS NOT NULL;

-- Every call to a provider is recorded. Feeds smart routing and the ops assistant.
CREATE TABLE payment_attempts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id  UUID NOT NULL REFERENCES payments(id),
    provider    TEXT NOT NULL,
    outcome     TEXT NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'TIMEOUT')),
    error_code  TEXT,
    latency_ms  INT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_attempts_provider_time ON payment_attempts (provider, created_at DESC);

-- ───────────── Idempotency ─────────────
-- Same (merchant, Idempotency-Key) always returns the original response.
CREATE TABLE idempotency_keys (
    merchant_id   UUID NOT NULL REFERENCES merchants(id),
    key           TEXT NOT NULL,
    request_hash  TEXT NOT NULL,               -- reject the same key reused with a different body
    status        TEXT NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_code INT,
    response_body JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, key)
);

-- ───────────── Double-entry ledger ─────────────
CREATE TABLE ledger_accounts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        TEXT NOT NULL UNIQUE,          -- e.g. 'provider_receivable:razorpay', 'merchant_payable:<id>'
    type        TEXT NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE')),
    merchant_id UUID REFERENCES merchants(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_transactions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference_type TEXT NOT NULL,              -- e.g. 'payment', 'settlement', 'refund'
    reference_id   UUID NOT NULL,
    description    TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (reference_type, reference_id, description)  -- never post the same event twice
);

-- Signed amounts: debits positive, credits negative. Every transaction must sum to zero.
CREATE TABLE ledger_entries (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES ledger_transactions(id),
    account_id     UUID NOT NULL REFERENCES ledger_accounts(id),
    amount_minor   BIGINT NOT NULL CHECK (amount_minor <> 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entries_account ON ledger_entries (account_id, created_at);
CREATE INDEX idx_entries_txn ON ledger_entries (transaction_id);

-- Checked at COMMIT (deferred), so all entries of a transaction can be inserted first.
CREATE FUNCTION check_ledger_balanced() RETURNS trigger AS $$
BEGIN
    IF (SELECT SUM(amount_minor) FROM ledger_entries
        WHERE transaction_id = NEW.transaction_id) <> 0 THEN
        RAISE EXCEPTION 'Ledger transaction % does not balance', NEW.transaction_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_ledger_balanced();

-- The ledger is append-only: mistakes are fixed with reversing entries, never edits.
CREATE FUNCTION reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER trg_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- ───────────── Transactional outbox ─────────────
-- Written in the same DB transaction as the state change; a relay publishes to Kafka.
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type TEXT NOT NULL,              -- e.g. 'payment'
    aggregate_id   UUID NOT NULL,              -- used as the Kafka message key (ordering per payment)
    event_type     TEXT NOT NULL,              -- e.g. 'payment.succeeded'
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
