-- Product search EXPLAIN (ANALYZE, BUFFERS) scenarios — mirrors ProductRepositoryImpl + ProductController defaults:
-- default size 12, sort LATEST => created_at DESC, id DESC; POPULARITY => popularity_score DESC, id DESC.
-- Run: .\scripts\explain\run-explain.ps1 (Postgres up)

\set ON_ERROR_STOP on

\echo ''
\echo '=== S1: list - no filters, default sort (LATEST / newest first), page 0 ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM products
ORDER BY created_at DESC, id DESC
OFFSET 0
LIMIT 12;

\echo ''
\echo '=== S2: list - category SHOES only ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM products
WHERE category = 'SHOES'
ORDER BY created_at DESC, id DESC
OFFSET 0
LIMIT 12;

\echo ''
\echo '=== S3: list - keyword (name OR brand ILIKE), Hibernate containsIgnoreCase pattern ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM products
WHERE name ILIKE '%Nike%' OR brand ILIKE '%Nike%'
ORDER BY created_at DESC, id DESC
OFFSET 0
LIMIT 12;

\echo ''
\echo '=== S4: list - complex filter (category + brand + gender + color + price range) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM products
WHERE category = 'SHOES'
  AND brand = 'Nike'
  AND gender = 'MEN'
  AND color = 'BLACK'
  AND price_amount >= 40000
  AND price_amount <= 180000
ORDER BY created_at DESC, id DESC
OFFSET 0
LIMIT 12;

\echo ''
\echo '=== S5: list - deep page (~page 833, size 12 => offset 9996) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM products
ORDER BY created_at DESC, id DESC
OFFSET 9996
LIMIT 12;

\echo ''
\echo '=== S6: list - POPULARITY sort ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM products
ORDER BY popularity_score DESC, id DESC
OFFSET 0
LIMIT 12;

\echo ''
\echo '=== S7: count - same predicates as S4 (pagination total query) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*)::bigint
FROM products
WHERE category = 'SHOES'
  AND brand = 'Nike'
  AND gender = 'MEN'
  AND color = 'BLACK'
  AND price_amount >= 40000
  AND price_amount <= 180000;

\echo ''
\echo '=== S8: count - no filters (full table cardinality for total) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*)::bigint
FROM products;
