-- At most one in-flight payment per order. A second attempt can't start while one is
-- CREATED or PROCESSING, which makes concurrent double charges impossible at the database level.
CREATE UNIQUE INDEX uq_payments_one_active_per_order
    ON payments (order_id)
    WHERE status IN ('CREATED', 'PROCESSING');