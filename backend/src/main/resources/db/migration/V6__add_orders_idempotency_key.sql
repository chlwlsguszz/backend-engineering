ALTER TABLE orders
    ADD COLUMN idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX uk_orders_member_idempotency_key
    ON orders (member_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
