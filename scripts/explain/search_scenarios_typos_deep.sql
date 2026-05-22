-- Edge EXPLAIN: typo keyword + multi-word keyword string
-- Matches ProductRepositoryImpl: lower(name) LIKE per token (pg_trgm GIN on lower(name)).
-- Run: .\scripts\explain\run-explain-edge.ps1

\set ON_ERROR_STOP on

\echo ''
\echo '=== E1: keyword typo (1) - Nikke (LIKE only, fast empty) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM products
WHERE lower(name) LIKE '%nikke%'
ORDER BY created_at DESC, id DESC
OFFSET 0
LIMIT 12;

\echo ''
\echo '=== E2: keyword combination (1) - multi-word AND ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM products
WHERE lower(name) LIKE '%nike%'
  AND lower(name) LIKE '%hoodie%'
ORDER BY created_at DESC, id DESC
OFFSET 0
LIMIT 12;
