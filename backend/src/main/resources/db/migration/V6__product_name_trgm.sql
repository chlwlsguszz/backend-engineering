-- Keyword search on products.name: pg_trgm GIN (matches lower(name) LIKE '%...%' in ProductRepositoryImpl).
-- On ~10M rows, index build can take several minutes and use significant disk.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_products_name_lower_trgm
    ON products
    USING gin (lower(name) gin_trgm_ops);
