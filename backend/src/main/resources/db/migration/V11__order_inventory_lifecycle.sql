-- Earlier releases did not retain the initial quantity after an order was edited.
-- Preserve the last-known quantity as the replay baseline for those existing rows.
ALTER TABLE orders ADD COLUMN requested_quantity integer;
UPDATE orders SET requested_quantity = quantity;
ALTER TABLE orders ALTER COLUMN requested_quantity SET NOT NULL;
ALTER TABLE orders ADD CONSTRAINT ck_orders_requested_quantity CHECK (requested_quantity > 0);

ALTER TABLE products ADD COLUMN version bigint NOT NULL DEFAULT 0;
