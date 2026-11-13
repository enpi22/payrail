-- Give the receipt uniqueness constraint an explicit, stable name so the application can
-- recognise this specific violation (instead of treating every integrity error as a duplicate).
ALTER TABLE orders
    RENAME CONSTRAINT orders_merchant_id_receipt_key TO uq_orders_merchant_receipt;