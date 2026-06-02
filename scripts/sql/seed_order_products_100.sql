-- Order load-test products (100 rows, fixed IDs).
-- Every run: delete fixture products (and their orders, FK) then insert fresh rows.
--
-- ID block: 10000501 .. 10000600 (first free ids after V2+V4: 500 + 10_000_000 = 10000500)
-- Name:     order-fix-001 .. order-fix-100
-- Price:    10000 + (index * 100)  → predictable total_amount in k6 audits
-- Stock:    50..200 (random each run)
-- Popularity: >= 5000 (random each run)
--
-- k6 env (example):
--   PRODUCT_ID_MIN=10000501  PRODUCT_ID_MAX=10000600
--   memberId: use existing seed members 1..50 (V2)
--
-- Run (repo root):
--   .\scripts\sql\run-seed-order-products.ps1

BEGIN;

DELETE FROM orders
WHERE product_id BETWEEN 10000501 AND 10000600;

DELETE FROM products
WHERE id BETWEEN 10000501 AND 10000600;

INSERT INTO products (
    id,
    name,
    price_amount,
    stock_quantity,
    description,
    category,
    brand,
    color,
    gender,
    status,
    popularity_score,
    updated_at
)
SELECT
    10000500 + gs AS id,
    'order-fix-' || lpad(gs::text, 3, '0') AS name,
    (10000 + (gs * 100))::numeric(19, 4) AS price_amount,
    (50 + floor(random() * 151)::int) AS stock_quantity,
    'Order k6 fixture product #' || gs AS description,
    'TOP' AS category,
    'OrderTestBrand' AS brand,
    'BLACK' AS color,
    'UNISEX' AS gender,
    'ACTIVE' AS status,
    (5000 + floor(random() * 5000)::int) AS popularity_score,
    CURRENT_TIMESTAMP AS updated_at
FROM generate_series(1, 100) AS gs;

SELECT setval(
    pg_get_serial_sequence('products', 'id'),
    GREATEST(
        (SELECT COALESCE(MAX(id), 1) FROM products),
        10000600
    )
);

COMMIT;

SELECT
    COUNT(*) AS fixture_count,
    MIN(id) AS min_id,
    MAX(id) AS max_id
FROM products
WHERE id BETWEEN 10000501 AND 10000600
  AND name LIKE 'order-fix-%';
