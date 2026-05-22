-- k6 complex-kw-cat mirror: keyword Shirt + category TOP (ProductRepositoryImpl).
-- Run: .\scripts\explain\run-explain-k6-slow.ps1

\set ON_ERROR_STOP on

\echo ''
\echo '=== K1: complex-kw-cat - Shirt + category BOTTOM ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM products
WHERE lower(name) LIKE '%shirt%'
  AND category = 'BOTTOM'
ORDER BY created_at DESC, id DESC
OFFSET 0
LIMIT 12;
