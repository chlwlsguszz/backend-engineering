-- Use the psql command printed by k6 order_idempotency.js after the run.
\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

WITH submitted AS (
    SELECT count(*) AS order_rows,
           count(*) FILTER (WHERE product_id = :'product'::bigint AND quantity = 1
                              AND status = 'CREATED') AS matching_rows
    FROM orders
    WHERE member_id = :'member'::bigint AND idempotency_key = :'key'
), inventory AS (
    SELECT (SELECT stock_quantity FROM products WHERE id = :'product'::bigint) AS stock_after
)
SELECT order_rows, matching_rows, :'before'::integer AS stock_before, stock_after,
       order_rows = 1 AND matching_rows = 1 AND
           stock_after IS NOT DISTINCT FROM (:'before'::integer - 1) AS passed
FROM submitted CROSS JOIN inventory
\gset

\echo Order rows :order_rows | matching CREATED rows :matching_rows | stock before :stock_before | after :stock_after
\if :passed
COMMIT;
\echo Order idempotency DB checks passed.
\else
DO $$ BEGIN RAISE EXCEPTION 'Expected one matching order and exactly one reserved unit.'; END; $$;
\endif
