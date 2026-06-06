-- Check stock for order fixture products (10000501..10001500).
-- Run:
--   .\scripts\sql\run-check-order-products-stock.ps1

SELECT
    id,
    name,
    stock_quantity,
    price_amount,
    status,
    popularity_score,
    updated_at
FROM products
WHERE id BETWEEN 10000501 AND 10001500
ORDER BY id;

-- Summary
SELECT
    COUNT(*) AS product_count,
    MIN(stock_quantity) AS min_stock,
    MAX(stock_quantity) AS max_stock,
    SUM(stock_quantity) AS total_stock
FROM products
WHERE id BETWEEN 10000501 AND 10001500;

