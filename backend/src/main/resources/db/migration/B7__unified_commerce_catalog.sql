-- Current schema and seed for a fresh database. Databases with Flyway
-- history continue on the V migrations; keep their identities and inventory.
SET LOCAL TIME ZONE 'UTC';

CREATE TEMP TABLE catalog_seed_settings (
    product_count integer NOT NULL CHECK (product_count BETWEEN 1 AND 10000000),
    as_of timestamptz NOT NULL
) ON COMMIT DROP;

INSERT INTO catalog_seed_settings VALUES ('${catalogProductCount}'::integer, '${catalogAsOf}'::timestamptz);

CREATE TABLE members (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    price_amount NUMERIC(19, 4) NOT NULL CHECK (price_amount >= 0),
    stock_quantity INT NOT NULL CHECK (stock_quantity >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    description TEXT NOT NULL DEFAULT '',
    category VARCHAR(100) NOT NULL DEFAULT 'TOP',
    brand VARCHAR(100) NOT NULL DEFAULT 'NIKE',
    color VARCHAR(50) NOT NULL DEFAULT 'BLACK',
    gender VARCHAR(20) NOT NULL DEFAULT 'UNISEX',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    popularity_score INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(19, 4) NOT NULL CHECK (unit_price >= 0),
    status VARCHAR(32) NOT NULL,
    total_amount NUMERIC(19, 4) NOT NULL CHECK (total_amount >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    idempotency_key VARCHAR(64),
    CONSTRAINT fk_orders_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT fk_orders_product FOREIGN KEY (product_id) REFERENCES products (id)
);

-- Deterministic catalog fixture for an empty database running PostgreSQL 16.
-- Independent hash inputs keep product attributes stable across catalog sizes.
CREATE FUNCTION pg_temp.catalog_draw(entity_id bigint, dimension text, buckets bigint)
RETURNS bigint
LANGUAGE SQL IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT (hashtextextended(dimension || ':' || entity_id::text, 42)
            & 9223372036854775807) % buckets
$$;

INSERT INTO members (id, email, password_hash, name, created_at)
SELECT id, 'user' || id || '@marketengine.local', 'dummy-password-hash',
       'User ' || id, settings.as_of - INTERVAL '400 days' + id * INTERVAL '1 hour'
FROM catalog_seed_settings settings CROSS JOIN generate_series(1, 50) AS member(id);

WITH models AS (
    SELECT model_id, settings.product_count, settings.as_of,
           pg_temp.catalog_draw(model_id, 'brand', 100) AS brand_rank,
           pg_temp.catalog_draw(model_id, 'category', 100) AS category_rank,
           1 + pg_temp.catalog_draw(model_id, 'style', 3)::integer AS style_slot,
           pg_temp.catalog_draw(model_id, 'color', 8)::integer AS first_color,
           pg_temp.catalog_draw(model_id, 'price', 100)::integer AS price_percentile,
           pg_temp.catalog_draw(model_id, 'popularity', 10000) AS popularity_rank,
           (ARRAY['MEN','WOMEN','UNISEX'])[1 + pg_temp.catalog_draw(model_id, 'gender', 3)::integer] AS gender,
           'ME' || lpad(model_id::text, 7, '0') AS model_code,
           settings.as_of
             - (1 + pg_temp.catalog_draw(model_id, 'release', 31536000000000))
               * INTERVAL '1 microsecond' AS released_at
    FROM catalog_seed_settings settings
    CROSS JOIN LATERAL generate_series(1, (settings.product_count + 3) / 4) AS model(model_id)
), assortment AS (
    SELECT models.*,
           CASE WHEN brand_rank < 25 THEN 'Nike'
                WHEN brand_rank < 43 THEN 'Adidas'
                WHEN brand_rank < 54 THEN 'New Balance'
                WHEN brand_rank < 62 THEN 'The North Face'
                ELSE (ARRAY['Puma','Under Armour','Converse','Reebok','Fila','Asics','Lululemon',
                            'Jordan','Vans','Skechers','Champion','Levis','Patagonia','Columbia',
                            'Oakley','Carhartt'])[1 + pg_temp.catalog_draw(model_id, 'tail-brand', 16)::integer]
           END AS brand,
           CASE WHEN brand_rank < 54 AND category_rank < 35 THEN 'SHOES'
                WHEN brand_rank BETWEEN 54 AND 61 AND category_rank < 45 THEN 'OUTER'
                WHEN category_rank < 48 THEN 'TOP'
                WHEN category_rank < 70 THEN 'BOTTOM'
                WHEN category_rank < 81 THEN 'OUTER'
                WHEN category_rank < 90 THEN 'SHOES'
                WHEN category_rank < 97 THEN 'HAT'
                ELSE 'GLASSES'
           END AS category
    FROM models
), styles AS (
    SELECT assortment.*,
           CASE category
               WHEN 'TOP' THEN (ARRAY['Hoodie','T-Shirt','Sweatshirt'])[style_slot]
               WHEN 'BOTTOM' THEN (ARRAY['Jeans','Slacks','Jogger Pants'])[style_slot]
               WHEN 'OUTER' THEN (ARRAY['Jacket','Coat','Windbreaker'])[style_slot]
               WHEN 'SHOES' THEN (ARRAY['Sneakers','Running Shoes','Boots'])[style_slot]
               WHEN 'HAT' THEN (ARRAY['Baseball Cap','Bucket Hat','Beanie'])[style_slot]
               ELSE (ARRAY['Round Glasses','Metal Frame','Sunglasses'])[style_slot]
           END AS item,
           CASE category
               WHEN 'HAT' THEN 12000 + 400 * price_percentile
               WHEN 'TOP' THEN 20000 + 800 * price_percentile
               WHEN 'BOTTOM' THEN 35000 + 900 * price_percentile
               WHEN 'SHOES' THEN 55000 + 1700 * price_percentile
               WHEN 'OUTER' THEN 70000 + 2700 * price_percentile
               ELSE 30000 + 1300 * price_percentile
           END AS price
    FROM assortment
), variants AS (
    SELECT styles.*, sku.id,
           (ARRAY['BLACK','WHITE','NAVY','GRAY','BEIGE','RED','BLUE','GREEN'])
             [1 + (first_color + variant.slot) % 8] AS color,
           pg_temp.catalog_draw(sku.id, 'availability', 100) AS availability,
           pg_temp.catalog_draw(sku.id, 'updated', 10000) AS update_rank
    FROM styles CROSS JOIN generate_series(0, 3) AS variant(slot)
    CROSS JOIN LATERAL (SELECT (model_id - 1) * 4 + variant.slot + 1 AS id) AS sku
    WHERE sku.id <= product_count
)
INSERT INTO products (id, name, price_amount, stock_quantity, description,
                      category, brand, color, gender, status, popularity_score, created_at, updated_at)
SELECT id, concat_ws(' ', brand, gender, item, model_code, initcap(color),
                    CASE WHEN id <= 13 THEN 'StudioCapsule' END), price,
       CASE WHEN availability < 15 THEN 0 ELSE 1 + pg_temp.catalog_draw(id, 'on-hand', 200)::integer END,
       concat_ws(' ', brand, item, model_code, lower(color)), category, brand, color, gender,
       CASE WHEN availability < 3 THEN 'DISCONTINUED' WHEN availability < 15 THEN 'OUT_OF_STOCK' ELSE 'ACTIVE' END,
       (popularity_rank * popularity_rank / 10000)::integer, released_at,
       CASE WHEN update_rank < 3000 THEN released_at
            ELSE released_at + (as_of - released_at)
                                  * (update_rank::double precision / 10000) END
FROM variants;

-- Historical orders predate or coincide with the snapshot. Today's stock is
-- net of earlier sales, cancellations and replenishments. No stock adjustment
-- is performed for these illustrative historical orders.
WITH order_draws AS (
    SELECT id, settings.as_of,
           1 + pg_temp.catalog_draw(id, 'buyer', 50) AS member_id,
           1 + pg_temp.catalog_draw(id, 'purchased-sku', settings.product_count) AS product_id,
           1 + pg_temp.catalog_draw(id, 'quantity', 5)::integer AS quantity,
           pg_temp.catalog_draw(id, 'order-status', 100) AS status_rank,
           pg_temp.catalog_draw(id, 'purchase-time', 10000) AS time_rank
    FROM catalog_seed_settings settings CROSS JOIN generate_series(1, 2000) AS history(id)
)
INSERT INTO orders (id, member_id, product_id, quantity, unit_price, status, total_amount,
                    idempotency_key, created_at)
SELECT d.id, d.member_id, d.product_id, d.quantity, p.price_amount,
       CASE WHEN d.status_rank < 80 THEN 'CONFIRMED'
            WHEN d.status_rank < 95 THEN 'CREATED' ELSE 'CANCELLED' END,
       d.quantity * p.price_amount, 'seed-order-' || d.id,
       GREATEST(p.created_at + INTERVAL '1 microsecond',
                d.as_of - (d.as_of - p.created_at) * (d.time_rank::double precision / 10000))
FROM order_draws d
JOIN products p ON p.id = d.product_id;

SELECT setval(pg_get_serial_sequence('members', 'id'), 50);
SELECT setval(pg_get_serial_sequence('products', 'id'), (SELECT product_count FROM catalog_seed_settings));
SELECT setval(pg_get_serial_sequence('orders', 'id'), 2000);

-- Build the search indexes after the heap has been populated (same definitions as V5/V6).
CREATE INDEX idx_products_created_at_id ON products (created_at DESC, id DESC);
CREATE INDEX idx_products_popularity_id ON products (popularity_score DESC, id DESC);
CREATE INDEX idx_products_filter_latest_id ON products (category, brand, gender, color, created_at DESC, id DESC);
CREATE INDEX idx_products_filter_popularity_id ON products (category, brand, gender, color, popularity_score DESC, id DESC);
CREATE UNIQUE INDEX uk_orders_member_idempotency_key ON orders (member_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ANALYZE members;
ANALYZE products;
ANALYZE orders;
