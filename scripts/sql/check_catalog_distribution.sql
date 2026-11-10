-- Audit the catalog immediately after seeding, before application writes.
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL TIME ZONE 'UTC';

SELECT to_regclass('catalog_seed_run') IS NOT NULL AS has_seed_metadata
\gset
\if :has_seed_metadata
\else
DO $$ BEGIN RAISE EXCEPTION 'Expected a fresh B9 pilot database. Check COMPOSE_PROJECT_NAME before retrying.'; END; $$;
\endif

SELECT count(*) = 1 AS one_seed_run FROM catalog_seed_run
\gset
\if :one_seed_run
\else
DO $$ BEGIN RAISE EXCEPTION 'Expected exactly one row in catalog_seed_run'; END; $$;
\endif

SELECT generator_version, seed, as_of, sku_count AS requested_skus FROM catalog_seed_run;
SELECT count(*) AS actual, count(*) FILTER (WHERE m.created_at > r.as_of) AS invalid_dates
FROM members m CROSS JOIN catalog_seed_run r
\gset member_
\echo Members :member_actual | post-snapshot member creation dates :member_invalid_dates

-- Aggregate orders once. Cancelled quantities are reported but consume no stock.
WITH order_totals AS (
    SELECT o.product_id, count(*) AS order_count,
           sum(o.quantity) FILTER (WHERE o.status IN ('CREATED', 'CONFIRMED')) AS sold,
           sum(o.quantity) FILTER (WHERE o.status = 'CANCELLED') AS cancelled,
           min(o.created_at) AS first_order, max(o.created_at) AS last_order,
           min(o.unit_price) AS min_sale_price, max(o.unit_price) AS max_sale_price,
           count(*) FILTER (WHERE o.quantity < 1 OR o.unit_price < 0
                               OR o.total_amount <> o.unit_price * o.quantity
                               OR o.status NOT IN ('CREATED', 'CONFIRMED', 'CANCELLED')
                               OR o.created_at < m.created_at) AS invalid_orders
    FROM orders o JOIN members m ON m.id = o.member_id
    GROUP BY o.product_id
), sku AS (
    SELECT p.*, i.product_id AS ledger_id, i.model_code, i.lineup, i.demand_score,
           i.opening_stock, i.received_stock, r.as_of, r.sku_count,
           o.order_count, coalesce(o.sold, 0) AS sold, o.cancelled, o.invalid_orders,
           o.first_order, o.last_order, o.min_sale_price, o.max_sale_price,
           ' ' || lower(p.name) || ' ' AS tokens
    FROM products p
    LEFT JOIN catalog_seed_inventory i ON i.product_id = p.id
    LEFT JOIN order_totals o ON o.product_id = p.id
    CROSS JOIN catalog_seed_run r
)
SELECT count(*) AS actual, coalesce(sum(order_count), 0) AS orders,
       coalesce(sum(opening_stock), 0) AS opening_units, coalesce(sum(received_stock), 0) AS received_units,
       coalesce(sum(stock_quantity), 0) AS current_units,
       coalesce(sum(sold), 0) AS counted_units, coalesce(sum(cancelled), 0) AS cancelled_units,
       count(*) FILTER (WHERE status = 'ACTIVE') AS active,
       count(*) FILTER (WHERE status = 'OUT_OF_STOCK') AS out_of_stock,
       count(*) FILTER (WHERE status = 'DISCONTINUED') AS discontinued,
       count(*) FILTER (WHERE demand_score >= 80) AS high_demand_skus,
       coalesce(sum(sold) FILTER (WHERE demand_score >= 80), 0) AS high_demand_units,
       count(*) FILTER (WHERE id NOT BETWEEN 1 AND sku_count OR ledger_id IS NULL
                            OR opening_stock < 0 OR received_stock < 0
                            OR demand_score NOT BETWEEN 1 AND 100
                            OR popularity_score <> demand_score * demand_score) AS invalid_inventory,
       count(*) FILTER (WHERE stock_quantity IS DISTINCT FROM
                         opening_stock::bigint + received_stock - sold) AS stock_mismatches,
       count(*) FILTER (WHERE status NOT IN ('ACTIVE', 'OUT_OF_STOCK', 'DISCONTINUED')
                            OR (stock_quantity > 0) <> (status = 'ACTIVE')
                            OR stock_quantity < 0) AS invalid_status,
       count(*) FILTER (WHERE created_at < as_of - interval '365 days'
                            OR created_at >= as_of OR updated_at < created_at OR updated_at > as_of
                            OR first_order < created_at OR last_order > updated_at) AS invalid_dates,
       count(*) FILTER (WHERE min_sale_price <> price_amount OR max_sale_price <> price_amount) AS price_mismatches,
       coalesce(sum(invalid_orders), 0) AS invalid_orders,
       count(*) FILTER (WHERE (id = 1 AND status = 'ACTIVE' AND stock_quantity = 120)
                           OR (id = 2 AND status = 'ACTIVE' AND stock_quantity = 1)
                           OR (id = 3 AND status = 'OUT_OF_STOCK' AND stock_quantity = 0)) AS valid_stock_probes,
       count(*) FILTER (WHERE category = 'TOP' AND color = 'BLACK') AS top_black,
       count(*) FILTER (WHERE brand = 'Oakley' AND color = 'GREEN') AS oakley_green,
       count(*) FILTER (WHERE strpos(tokens, ' nike ') > 0) AS nike,
       count(*) FILTER (WHERE strpos(tokens, ' city000001 ') > 0) AS city,
       count(*) FILTER (WHERE strpos(tokens, ' drop000001 ') > 0) AS drop_line,
       count(*) FILTER (WHERE strpos(tokens, ' studiocapsule ') > 0) AS capsule,
       count(*) FILTER (WHERE (strpos(tokens, ' studiocapsule ') > 0) <> (id BETWEEN 1 AND 13)) AS wrong_capsule_ids,
       count(*) FILTER (WHERE strpos(tokens, ' me0000001 ') > 0) AS first_model,
       count(*) FILTER (WHERE model_code = 'ME0000001') AS expected_first_model,
       count(*) FILTER (WHERE strpos(tokens, ' me0000000 ') > 0) AS missing_model,
       count(*) FILTER (WHERE nullif(lineup, '') IS NULL OR nullif(model_code, '') IS NULL
                            OR strpos(tokens, ' ' || lower(lineup) || ' ') = 0
                            OR strpos(tokens, ' ' || lower(model_code) || ' ') = 0) AS missing_tokens
FROM sku
\gset sku_

\echo SKUs :sku_actual | orders :sku_orders | units sold :sku_counted_units | units cancelled :sku_cancelled_units
\echo Inventory: opening :sku_opening_units + receipts :sku_received_units - reserved :sku_counted_units = current :sku_current_units
\echo Invalid inventory :sku_invalid_inventory | stock mismatches :sku_stock_mismatches | invalid statuses :sku_invalid_status
\echo Invalid dates :sku_invalid_dates | invalid orders :sku_invalid_orders | SKUs with price mismatches :sku_price_mismatches
\echo Missing lineup or model tokens :sku_missing_tokens | wrong StudioCapsule IDs :sku_wrong_capsule_ids | valid stock probes :sku_valid_stock_probes
\echo ACTIVE :sku_active | OUT_OF_STOCK :sku_out_of_stock | DISCONTINUED :sku_discontinued

SELECT demand_score_range, skus, sold_units, round(sold_units::numeric / nullif(skus, 0), 3) AS sold_units_per_sku
FROM (VALUES ('1-79', :sku_actual - :sku_high_demand_skus, :sku_counted_units - :sku_high_demand_units),
             ('80-100', :sku_high_demand_skus, :sku_high_demand_units)) AS demand(demand_score_range, skus, sold_units);

-- Counts refer to all seeded DB product names. Case-insensitive whole tokens
-- keep the expected ground truth independent of any Elasticsearch analyzer.
SELECT * FROM (VALUES
    ('category=TOP and color=BLACK', :sku_top_black),
    ('brand=Oakley and color=GREEN', :sku_oakley_green),
    ('Nike', :sku_nike), ('City000001', :sku_city), ('Drop000001', :sku_drop_line),
    ('StudioCapsule', :sku_capsule), ('ME0000001', :sku_first_model), ('ME0000000', :sku_missing_model)
) AS probes(probe, matching_skus);

SELECT p.id, p.name, p.status, i.opening_stock, i.received_stock, p.stock_quantity
FROM products p JOIN catalog_seed_inventory i ON i.product_id = p.id
WHERE p.id BETWEEN 1 AND 3 ORDER BY p.id;

-- Count SKU variants per model rather than treating complete blocks and a
-- truncated final model as having identical sizes.
WITH model_sizes AS (
    SELECT i.model_code, count(*) AS variants, count(DISTINCT p.color) AS colors,
           count(DISTINCT (p.brand, i.lineup, p.category, p.gender, p.price_amount)) AS model_definitions
    FROM catalog_seed_inventory i JOIN products p ON p.id = i.product_id
    GROUP BY i.model_code
), histogram AS (
    SELECT variants, count(*) AS models, count(*) FILTER (WHERE colors <> variants) AS duplicate_colors,
           count(*) FILTER (WHERE model_definitions <> 1) AS conflicting_models
    FROM model_sizes GROUP BY variants
)
SELECT coalesce(sum(models), 0) AS models,
       coalesce(json_object_agg(variants, models ORDER BY variants), '{}'::json) AS option_histogram,
       coalesce(sum(duplicate_colors), 0) AS duplicate_color_models,
       coalesce(sum(conflicting_models), 0) AS conflicting_models,
       coalesce(sum(models) FILTER (WHERE variants NOT BETWEEN 1 AND 7), 0) AS invalid_sizes
FROM histogram
\gset model_
\echo Models :model_models | options per model (SKU count to model count) :model_option_histogram
\echo Models with repeated colors :model_duplicate_color_models | models outside 1 to 7 variants :model_invalid_sizes
\echo Models with inconsistent brand, lineup, category, gender or price :model_conflicting_models

WITH lineups AS (
    SELECT i.lineup, count(*) AS skus, count(DISTINCT p.brand) AS brands
    FROM catalog_seed_inventory i JOIN products p ON p.id = i.product_id GROUP BY i.lineup
)
SELECT count(*) AS total, count(*) FILTER (WHERE brands <> 1) AS conflicting_owners,
       count(*) FILTER (WHERE skus <= 50) AS small,
       count(*) FILTER (WHERE skus BETWEEN 51 AND 200) AS medium,
       count(*) FILTER (WHERE skus > 200) AS broad FROM lineups
\gset lineup_
\echo Lineups :lineup_total | up to 50 SKUs :lineup_small | 51-200 :lineup_medium | above 200 :lineup_broad | conflicting brands :lineup_conflicting_owners

WITH facet_counts AS (
    SELECT CASE WHEN grouping(brand) = 0 THEN 'brand'
                WHEN grouping(category) = 0 THEN 'category' ELSE 'color' END AS facet,
           coalesce(brand, category, color) AS value, count(*) AS skus
    FROM products GROUP BY GROUPING SETS ((brand), (category), (color))
)
SELECT facet, value, skus, round(100.0 * skus / sum(skus) OVER (PARTITION BY facet), 2) AS pct
FROM facet_counts ORDER BY facet, skus DESC, value;

SELECT category, count(*) AS skus,
       round(100.0 * count(*) FILTER (WHERE color = 'BLACK') / count(*), 2) AS black_pct,
       round(100.0 * count(*) FILTER (WHERE color = 'GREEN') / count(*), 2) AS green_pct
FROM products GROUP BY category ORDER BY category;

SELECT date_trunc('month', created_at)::date AS release_month, count(*) AS skus
FROM products GROUP BY 1 ORDER BY 1;

SELECT :sku_actual = sku_count
       AND :member_actual = greatest(50, (sku_count + 99) / 100) AND :member_invalid_dates = 0
       AND :sku_stock_mismatches = 0 AND :sku_invalid_inventory = 0 AND :sku_invalid_status = 0
       AND :sku_invalid_dates = 0 AND :sku_invalid_orders = 0 AND :sku_price_mismatches = 0
       AND :sku_valid_stock_probes = least(3, sku_count)
       AND :sku_capsule = least(13, sku_count) AND :sku_wrong_capsule_ids = 0
       AND :sku_drop_line = least(32, sku_count)
       AND :sku_city = least(128, greatest(sku_count - 32, 0))
       AND :sku_first_model = :sku_expected_first_model AND :sku_first_model = least(4, sku_count)
       AND :sku_missing_model = 0 AND :sku_missing_tokens = 0
       AND :model_duplicate_color_models = 0 AND :model_invalid_sizes = 0
       AND :model_conflicting_models = 0 AND :lineup_conflicting_owners = 0 AS checks_passed
FROM catalog_seed_run
\gset
\if :checks_passed
COMMIT;
\echo Catalog seed checks passed.
\else
DO $$ BEGIN RAISE EXCEPTION 'Catalog seed checks failed. See the counts above.'; END; $$;
\endif
