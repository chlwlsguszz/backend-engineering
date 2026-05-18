-- Edge EXPLAIN: typo keyword (1) + multi-word keyword string (1) + very deep pages (2).
-- Matches ProductRepositoryImpl: LIKE + word_similarity per token (pg_trgm).
-- Run: .\scripts\explain\run-explain-edge.ps1

\set ON_ERROR_STOP on

\echo ''
\echo '=== E1: keyword typo (1) — Nikke (substring OR word_similarity > 0.35) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM products
WHERE lower(name) LIKE '%nikke%'
   OR word_similarity('nikke', lower(name)) > 0.35
ORDER BY created_at DESC, id DESC
OFFSET 0
LIMIT 12;

\echo ''
\echo '=== E2: keyword combination (1) — multi-word AND (name is "Nike MEN Hoodie ...", not contiguous "nike hoodie") ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM products
WHERE (lower(name) LIKE '%nike%' OR word_similarity('nike', lower(name)) > 0.35)
  AND (lower(name) LIKE '%hoodie%' OR word_similarity('hoodie', lower(name)) > 0.35)
ORDER BY created_at DESC, id DESC
OFFSET 0
LIMIT 12;

\echo ''
\echo '=== E3: very deep page (1/2) — LATEST, OFFSET 9840000 (page 820000, size 12) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM products
ORDER BY created_at DESC, id DESC
OFFSET 9840000
LIMIT 12;

\echo ''
\echo '=== E4: very deep page (2/2) — POPULARITY, OFFSET 9996000 (page 833000, size 12) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM products
ORDER BY popularity_score DESC, id DESC
OFFSET 9996000
LIMIT 12;
