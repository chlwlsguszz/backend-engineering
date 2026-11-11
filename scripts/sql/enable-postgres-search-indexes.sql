-- Optional indexes for MARKETENGINE_SEARCH_BACKEND=postgres.
-- Run with psql in autocommit mode, never psql --single-transaction or Flyway.
-- ES-only installations do not need to build a large name index.
\set ON_ERROR_STOP on

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Match the actual query expression: lower(name) LIKE '%token%' ESCAPE '!'.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_products_name_lower_trgm
    ON products USING gin (lower(name) gin_trgm_ops);

-- The six-column all-filter index cannot directly order category-only results.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_products_category_latest_id
    ON products (category, created_at DESC, id DESC);

ANALYZE products;

-- A cancelled CREATE INDEX CONCURRENTLY can leave an invalid index behind.
-- Fail explicitly instead of considering an existing invalid index ready for use.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE c.relname IN ('idx_products_name_lower_trgm', 'idx_products_category_latest_id')
          AND NOT i.indisvalid
    ) THEN
        RAISE EXCEPTION 'Search index is invalid. Drop the named invalid index CONCURRENTLY and rerun this script.';
    END IF;
END
$$;
