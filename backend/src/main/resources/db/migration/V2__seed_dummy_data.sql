-- Baseline load-test seed data (PostgreSQL)
-- members: 50, products: 500, orders: 2000

INSERT INTO members (email, password_hash, name)
SELECT
    'user' || gs || '@marketengine.local',
    'dummy-password-hash',
    'User ' || gs
FROM generate_series(1, 50) AS gs;

INSERT INTO products (name, price_amount, stock_quantity)
SELECT
    'Product ' || gs,
    round((10 + random() * 990)::numeric, 2),
    floor(random() * 200)::int
FROM generate_series(1, 500) AS gs;

WITH sampled_orders AS (
    SELECT
        (1 + floor(random() * 50))::bigint AS member_id,
        (1 + floor(random() * 500))::bigint AS product_id,
        (1 + floor(random() * 5))::int AS quantity,
        CASE
            WHEN random() < 0.8 THEN 'CREATED'
            WHEN random() < 0.95 THEN 'CONFIRMED'
            ELSE 'CANCELLED'
        END AS status
    FROM generate_series(1, 2000)
)
INSERT INTO orders (member_id, product_id, quantity, unit_price, status, total_amount)
SELECT
    s.member_id,
    s.product_id,
    s.quantity,
    p.price_amount,
    s.status,
    (p.price_amount * s.quantity)::numeric(19, 4)
FROM sampled_orders s
JOIN products p ON p.id = s.product_id;
