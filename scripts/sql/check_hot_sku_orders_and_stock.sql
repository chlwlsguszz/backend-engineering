-- Evidence query for oversell / consistency checks (hot SKU).
-- Default hot SKU: product_id = 10000501 (order-fix-001).
--
-- Run:
--   .\scripts\sql\run-check-hot-sku.ps1
--
-- If you want a different product id, edit the first CTE value.

WITH params AS (
    SELECT 10000501::bigint AS product_id
)
SELECT
    p.id,
    p.name,
    p.stock_quantity,
    p.price_amount,
    p.status,
    p.popularity_score,
    p.updated_at
FROM products p
JOIN params par ON par.product_id = p.id;

WITH params AS (
    SELECT 10000501::bigint AS product_id
)
SELECT
    COUNT(*) AS order_count,
    COALESCE(SUM(quantity), 0) AS total_quantity,
    MIN(created_at) AS first_order_at,
    MAX(created_at) AS last_order_at
FROM "orders" o
JOIN params par ON par.product_id = o.product_id;

-- Sample latest orders
WITH params AS (
    SELECT 10000501::bigint AS product_id
)
SELECT
    o.id,
    o.member_id,
    o.product_id,
    o.quantity,
    o.unit_price,
    o.total_amount,
    o.status,
    o.created_at
FROM "orders" o
JOIN params par ON par.product_id = o.product_id
ORDER BY o.id DESC
LIMIT 30;

