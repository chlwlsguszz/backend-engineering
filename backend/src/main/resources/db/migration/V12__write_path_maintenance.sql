-- Keep maintenance proportional to the size of the write-heavy tables.
-- fillfactor changes future page allocations; this migration does not rewrite the catalogue.
ALTER TABLE products SET (
    fillfactor = 90,
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_vacuum_threshold = 1000,
    autovacuum_analyze_scale_factor = 0.01
);

ALTER TABLE product_search_outbox SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_vacuum_threshold = 100,
    autovacuum_analyze_scale_factor = 0.02,
    autovacuum_analyze_threshold = 100
);

-- Statistics are queryable when pg_stat_statements is preloaded by PostgreSQL.
-- Creating the extension also works in the isolated test databases without preloading.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
