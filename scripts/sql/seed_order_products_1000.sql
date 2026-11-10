-- Export order targets from one MVCC snapshot of the existing product catalog.
-- The legacy filename is kept for the PowerShell entry point. This query is read-only.
\if :{?seed}
\else
\set seed 42
\endif
\if :{?sample_count}
\else
\set sample_count 1000
\endif

WITH settings AS (
    SELECT :'seed'::integer AS seed, LEAST(1000, GREATEST(1, :'sample_count'::integer)) AS requested
),
catalog AS NOT MATERIALIZED (
    SELECT id, stock_quantity, price_amount, popularity_score, status
    FROM products
    -- k6 reads JSON IDs as JavaScript numbers.
    WHERE id BETWEEN 1 AND 9007199254740991 AND brand <> 'OrderTestBrand'
),
eligible AS NOT MATERIALIZED (
    SELECT id, stock_quantity, price_amount, popularity_score
    FROM catalog
    WHERE status = 'ACTIVE' AND stock_quantity > 0
),
bounds AS (
    SELECT MIN(id) AS first_id, MAX(id) AS last_id FROM catalog
),
buckets AS (
    SELECT bucket, seed,
           first_id + (last_id - first_id + 1) * bucket / requested AS lo,
           first_id + (last_id - first_id + 1) * (bucket + 1) / requested AS hi
    FROM bounds CROSS JOIN settings
    CROSS JOIN LATERAL generate_series(0, requested - 1) AS series(bucket)
    WHERE first_id IS NOT NULL
),
pivots AS (
    -- Disjoint half-open ID ranges. Wrap inside a range if its tail is empty.
    SELECT lo, hi, lo + (hashtextextended(bucket::text, seed) & 9223372036854775807)
                         % NULLIF(hi - lo, 0) AS pivot
    FROM buckets
    WHERE hi > lo
),
picks AS MATERIALIZED (
    SELECT COALESCE(
        (SELECT id FROM eligible WHERE id >= pivot AND id < hi ORDER BY id LIMIT 1),
        (SELECT id FROM eligible WHERE id >= lo AND id < pivot ORDER BY id LIMIT 1)
    ) AS id
    FROM pivots
),
sample AS (
    SELECT id FROM picks WHERE id IS NOT NULL
    UNION ALL
    (SELECT id FROM eligible
     WHERE id NOT IN (SELECT id FROM picks WHERE id IS NOT NULL)
     ORDER BY id
     LIMIT (SELECT requested - (SELECT COUNT(id) FROM picks) FROM settings))
)
SELECT json_build_object(
    'schemaVersion', 2,
    'seed', settings.seed,
    'requestedCount', settings.requested,
    -- Seeded members form the inclusive range 1..memberCount. A gap must not
    -- turn into requests for missing members in an order experiment.
    'memberCount', (SELECT CASE WHEN min(id) = 1 AND max(id) = count(*) THEN count(*) END FROM members),
    'generatedAt', statement_timestamp(),
    'catalog', json_build_object('database', current_database(), 'minProductId', first_id, 'maxProductId', last_id),
    'targets', COALESCE((
        SELECT json_agg(json_build_object(
                   'productId', e.id, 'initialStock', e.stock_quantity, 'unitPrice', e.price_amount::text,
                   'popularityScore', e.popularity_score
               ) ORDER BY hashtextextended(e.id::text, settings.seed), e.id)
        FROM sample JOIN eligible AS e ON e.id = sample.id
    ), '[]'::json)
)
FROM settings CROSS JOIN bounds;
