-- Inputs from the isolated run's setup: prefix, opening (JSON array of {id,stock}).
WITH opening AS (
    SELECT * FROM jsonb_to_recordset(:'opening'::jsonb) AS sku(id bigint, stock bigint)
), sold AS (
    SELECT product_id, count(*) AS orders, sum(quantity) AS units
    FROM orders WHERE starts_with(idempotency_key, :'prefix') GROUP BY product_id
)
SELECT json_build_object(
    'orders', coalesce(sum(sold.orders), 0), 'units', coalesce(sum(sold.units), 0),
    'checkedSkus', count(opening.id),
    'mismatches', count(*) FILTER (WHERE opening.id IS NULL OR p.id IS NULL
        OR opening.stock - coalesce(sold.units, 0) <> p.stock_quantity))
FROM opening FULL JOIN sold ON sold.product_id = opening.id
LEFT JOIN products p ON p.id = opening.id;
