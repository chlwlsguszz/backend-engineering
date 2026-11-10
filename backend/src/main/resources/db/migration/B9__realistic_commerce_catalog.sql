-- Baseline for fresh databases. An existing V8 catalog is not reseeded.
SET LOCAL TIME ZONE 'UTC';

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
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_products_date_order CHECK (updated_at >= created_at)
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

-- Keep reproducibility and reconciliation data outside the application payloads.
CREATE TABLE catalog_seed_run (
    singleton boolean PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    generator_version text NOT NULL,
    sku_count integer NOT NULL CHECK (sku_count BETWEEN 1 AND 10000000),
    member_count integer GENERATED ALWAYS AS (greatest(50, (sku_count + 99) / 100)) STORED,
    seed bigint NOT NULL,
    as_of timestamptz NOT NULL CHECK (isfinite(as_of))
);
INSERT INTO catalog_seed_run (generator_version, sku_count, seed, as_of)
VALUES ('catalog-v9', '${catalogProductCount}'::integer, '${catalogSeed}'::bigint, '${catalogAsOf}'::timestamptz);

CREATE TABLE catalog_seed_inventory (
    product_id bigint PRIMARY KEY REFERENCES products (id) ON DELETE CASCADE,
    model_code text NOT NULL,
    lineup text NOT NULL,
    is_limited boolean NOT NULL,
    demand_score integer NOT NULL CHECK (demand_score BETWEEN 1 AND 100),
    opening_stock integer NOT NULL CHECK (opening_stock >= 0),
    received_stock integer NOT NULL CHECK (received_stock >= 0)
);

CREATE FUNCTION pg_temp.catalog_draw(entity_id bigint, dimension text, seed bigint, buckets bigint)
RETURNS bigint
LANGUAGE SQL IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT (hashtextextended(dimension || ':' || entity_id::text, seed)
            & 9223372036854775807) % buckets
$$;

INSERT INTO members (id, email, password_hash, name, created_at)
SELECT id, 'user' || id || '@marketengine.local', 'dummy-password-hash',
       'User ' || id, run.as_of - INTERVAL '400 days'
                        + pg_temp.catalog_draw(id, 'signup-hour', run.seed, 30 * 24) * INTERVAL '1 hour'
FROM catalog_seed_run run CROSS JOIN LATERAL generate_series(1, run.member_count) AS member(id);

-- Prefix-stable families: a complete block has 32 SKU slots across eight models.
-- The final block can stop inside a model. Gaps and global cumulative sums are unnecessary.
CREATE TEMP TABLE seed_catalog_skus ON COMMIT DROP AS
WITH family_slots(model_slot, first_slot, color_count) AS (
    VALUES (0, 0, 4), (1, 4, 2), (2, 6, 5), (3, 11, 1),
           (4, 12, 7), (5, 19, 3), (6, 22, 6), (7, 28, 4)
), blocks AS (
    SELECT r.seed, r.as_of, r.sku_count, b.block_no, start_slot.model_slot AS pivot,
           start_slot.first_slot AS first_pivot_sku
    FROM catalog_seed_run r
    CROSS JOIN LATERAL generate_series(0, (r.sku_count - 1) / 32) AS b(block_no)
    JOIN family_slots start_slot
      ON start_slot.model_slot = CASE WHEN b.block_no = 0 THEN 0
            ELSE pg_temp.catalog_draw(b.block_no, 'rotate-family-order', r.seed, 8)::integer END
), families AS (
    -- Rotate the size pattern at model boundaries. Every full block still
    -- consumes exactly 32 slots so sampling a larger prefix preserves IDs.
    SELECT b.seed, b.as_of, b.block_no,
           1 + b.block_no * 8 + (s.model_slot - b.pivot + 8) % 8 AS model_id,
           bounds.first_sku, least(s.color_count, b.sku_count - bounds.first_sku + 1) AS color_count
    FROM blocks b
    CROSS JOIN family_slots s
    CROSS JOIN LATERAL (SELECT 1 + b.block_no * 32 + (s.first_slot - b.first_pivot_sku + 32) % 32 AS first_sku) bounds
    WHERE bounds.first_sku <= b.sku_count
), owners AS (
    -- Numbered collections own one brand across their whole group of blocks.
    -- Permanent lines below are explicitly named for the owning brand.
    SELECT f.*,
           CASE WHEN block_no % 16 BETWEEN 0 AND 4 THEN block_no / 16 ELSE model_id END AS brand_key,
           CASE WHEN block_no % 16 = 0 THEN 'drop-brand'
                WHEN block_no % 16 BETWEEN 1 AND 4 THEN 'city-brand'
                ELSE 'model-brand' END AS brand_dimension
    FROM families f
), draws AS (
    SELECT f.*,
           pg_temp.catalog_draw(brand_key, brand_dimension, seed, 100) AS brand_rank,
           pg_temp.catalog_draw(model_id, 'category', seed, 100) AS category_rank,
           pg_temp.catalog_draw(model_id, 'lineup', seed, 100) AS lineup_rank,
           1 + pg_temp.catalog_draw(model_id, 'item', seed, 4)::integer AS item_slot,
           pg_temp.catalog_draw(model_id, 'price', seed, 100)::integer AS price_percentile
    FROM owners f
), labels AS (
    SELECT d.*,
           CASE WHEN brand_rank < 25 THEN 'Nike'
                WHEN brand_rank < 43 THEN 'Adidas'
                WHEN brand_rank < 55 THEN 'New Balance'
                WHEN brand_rank < 85 THEN (ARRAY['The North Face','Puma','Under Armour','Asics','Converse','Patagonia'])
                    [1 + ((brand_rank - 55) / 5)::integer]
                ELSE (ARRAY['Reebok','Fila','Lululemon','Jordan','Vans','Skechers','Champion','Levis',
                            'Columbia','Oakley','Carhartt'])[1 + pg_temp.catalog_draw(brand_key, brand_dimension || '-tail', seed, 11)::integer]
           END AS brand,
           CASE WHEN block_no % 16 = 0 THEN 'drop'
                WHEN block_no % 16 BETWEEN 1 AND 4 THEN 'city'
                WHEN lineup_rank < 48 THEN 'daily'
                WHEN lineup_rank < 76 THEN 'pace'
                WHEN lineup_rank < 92 THEN 'trail'
                ELSE 'studio'
           END AS line_family
    FROM draws d
), assortments AS (
    SELECT l.*,
           -- A full 16-block group gives its Drop code 32 SKUs and its City code 128 SKUs.
           CASE line_family WHEN 'drop' THEN 'Drop' || lpad((1 + block_no / 16)::text, 6, '0')
                            WHEN 'city' THEN 'City' || lpad((1 + block_no / 16)::text, 6, '0')
                            ELSE replace(brand, ' ', '') ||
                                CASE line_family WHEN 'daily' THEN 'Everyday' WHEN 'pace' THEN 'PaceLine'
                                     WHEN 'trail' THEN 'TrailSeries' ELSE 'StudioLine' END
           END AS lineup,
           CASE WHEN brand IN ('Nike','Adidas','New Balance','Asics') AND category_rank < 35 THEN 'SHOES'
                WHEN brand IN ('The North Face','Patagonia','Columbia') AND category_rank < 45 THEN 'OUTER'
                WHEN brand = 'Oakley' AND category_rank < 35 THEN 'GLASSES'
                WHEN category_rank < 48 THEN 'TOP'
                WHEN category_rank < 70 THEN 'BOTTOM'
                WHEN category_rank < 80 THEN 'OUTER'
                WHEN category_rank < 90 THEN 'SHOES'
                WHEN category_rank < 96 THEN 'HAT'
                ELSE 'GLASSES' END AS category,
           line_family = 'drop' AS is_limited,
           (ARRAY['MEN','WOMEN','UNISEX'])[1 + pg_temp.catalog_draw(model_id, 'gender', seed, 3)::integer] AS gender,
           'ME' || lpad(model_id::text, 7, '0') AS model_code
    FROM labels l
), style AS (
    SELECT a.*,
           CASE category WHEN 'TOP' THEN (ARRAY['Hoodie','T-Shirt','Sweatshirt','Long Sleeve'])[item_slot]
                         WHEN 'BOTTOM' THEN (ARRAY['Jeans','Slacks','Jogger Pants','Cargo Pants'])[item_slot]
                         WHEN 'OUTER' THEN (ARRAY['Jacket','Coat','Windbreaker','Cardigan'])[item_slot]
                         WHEN 'SHOES' THEN (ARRAY['Sneakers','Running Shoes','Boots','Loafers'])[item_slot]
                         WHEN 'HAT' THEN (ARRAY['Baseball Cap','Bucket Hat','Beanie','Visor'])[item_slot]
                         ELSE (ARRAY['Round Glasses','Metal Frame','Sunglasses','Square Glasses'])[item_slot]
           END AS item,
           CASE category WHEN 'HAT' THEN 12000 + 400 * price_percentile
                         WHEN 'TOP' THEN 20000 + 800 * price_percentile
                         WHEN 'BOTTOM' THEN 35000 + 900 * price_percentile
                         WHEN 'SHOES' THEN 55000 + 1700 * price_percentile
                         WHEN 'OUTER' THEN 70000 + 2700 * price_percentile
                         ELSE 30000 + 1300 * price_percentile END AS price,
           -- Earlier entries have higher sampling weights. Draw colors without replacement.
           CASE WHEN line_family = 'drop' AND category IN ('TOP','SHOES') THEN
                     ARRAY['RED','WHITE','BLACK','BLUE','GRAY','NAVY','BEIGE','GREEN']
                WHEN line_family = 'trail' THEN
                     ARRAY['GREEN','NAVY','BEIGE','BLACK','GRAY','BLUE','RED','WHITE']
                WHEN category IN ('BOTTOM','GLASSES','OUTER') THEN
                     ARRAY['BLACK','NAVY','GRAY','BEIGE','WHITE','BLUE','GREEN','RED']
                WHEN category = 'SHOES' THEN
                     ARRAY['WHITE','BLACK','GRAY','NAVY','RED','BLUE','BEIGE','GREEN']
                ELSE ARRAY['BLACK','WHITE','NAVY','GRAY','BEIGE','BLUE','RED','GREEN']
           END AS palette,
           -- Release waves occupy short windows about a quarter apart. Other
           -- families roll across the full 365-day interval at microsecond resolution.
           CASE WHEN line_family IN ('drop','trail') THEN
                    86400000000::bigint * (4 + 90 * pg_temp.catalog_draw(
                            CASE WHEN is_limited THEN block_no / 16 ELSE model_id END, 'wave', seed, 4))
                        + pg_temp.catalog_draw(model_id, 'wave-offset', seed, 604800000000)
                ELSE pg_temp.catalog_draw(model_id, 'rolling-release', seed, 31536000000000)
           END AS release_age_us
    FROM assortments a
), palettes AS (
    SELECT style.*, chosen.colors
    FROM style
    CROSS JOIN LATERAL (
        -- A weighted permutation of at most eight options per model. Taking a prefix
        -- preserves existing SKU colors when the last family grows at larger scales.
        SELECT array_agg(palette[p.rank] ORDER BY
                   -ln((1 + pg_temp.catalog_draw(model_id, 'color:' || palette[p.rank], seed, 1000000))
                       / 1000001.0::double precision)
                   / (ARRAY[70,42,25,16,8,5,3,1])[p.rank], p.rank) AS colors
        FROM generate_series(1, 8) AS p(rank)
    ) chosen
), skus AS (
    SELECT first_sku + v.variant_slot AS id, model_code, lineup, brand, category, gender,
           colors[1 + v.variant_slot] AS color, item, price,
           least(100, 1 + pg_temp.catalog_draw(model_id, 'demand', seed, 66)::integer
                        + CASE WHEN brand_rank < 55 THEN 14 ELSE 0 END
                        + CASE WHEN is_limited THEN 14 ELSE 0 END
                        + CASE WHEN v.variant_slot = 0 THEN 6 ELSE 0 END) AS demand_score,
           is_limited, as_of - (1 + release_age_us) * INTERVAL '1 microsecond' AS released_at,
           as_of, seed
    FROM palettes CROSS JOIN LATERAL generate_series(0, color_count - 1) AS v(variant_slot)
), revisions AS (
    SELECT s.*, pg_temp.catalog_draw(id, 'last-change', seed, 10000) AS revision_rank
    FROM skus s
)
SELECT id, model_code, lineup, brand, category, gender, color, item, price, demand_score,
       is_limited, released_at,
       CASE WHEN revision_rank < 3000 THEN released_at
            ELSE least(as_of, released_at + (as_of - released_at) * (revision_rank::double precision / 10000))
       END AS updated_at,
       id > 3 AND pg_temp.catalog_draw(id, 'discontinued', seed, 100) < 3 AS discontinued
FROM revisions;

ANALYZE seed_catalog_skus;

-- Aggregate supply is available to this history; receipt dates are not modeled.
-- Opening units and subsequent receipts are independent of actual purchases.
CREATE FUNCTION pg_temp.catalog_supply(sku bigint, limited boolean, retired boolean, demand integer, seed_value bigint)
RETURNS TABLE(opening_stock integer, received_stock integer)
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $supply$
    SELECT CASE WHEN sku = 1 THEN 120 WHEN sku = 2 THEN 2 WHEN sku = 3 THEN 1
                WHEN retired THEN 0
                WHEN limited THEN pg_temp.catalog_draw(sku, 'opening-stock', seed_value, 5)::integer
                ELSE pg_temp.catalog_draw(sku, 'opening-stock', seed_value, 81)::integer END,
           CASE WHEN sku = 1 THEN 10 WHEN sku IN (2, 3) THEN 1
                WHEN retired THEN 0
                WHEN pg_temp.catalog_draw(sku, 'delivery', seed_value, 100)
                    >= CASE WHEN limited THEN 8 ELSE 12 + demand / 2 END THEN 0
                WHEN limited THEN 1 + pg_temp.catalog_draw(sku, 'receipt-size', seed_value, 3)::integer
                ELSE 1 + pg_temp.catalog_draw(sku, 'receipt-size', seed_value, 35)::integer END
$supply$;

-- Expand only eligible SKUs. Popular items receive more order attempts and
-- larger requested quantities. Each event occupies its own chronological slot.
CREATE TEMP TABLE seed_catalog_orders ON COMMIT DROP AS
WITH eligible AS MATERIALIZED (
    SELECT s.id AS product_id, s.released_at, s.price, s.demand_score, r.seed, r.as_of, r.member_count,
           stock.opening_stock + stock.received_stock AS available,
           CASE WHEN s.id = 1 THEN 3 WHEN s.id IN (2, 3) THEN 1
                ELSE 1 + pg_temp.catalog_draw(s.id, 'repeat-orders', r.seed,
                                               1 + s.demand_score / 40)::integer END AS attempts
    FROM seed_catalog_skus s
    CROSS JOIN catalog_seed_run r
    CROSS JOIN LATERAL pg_temp.catalog_supply(s.id, s.is_limited, s.discontinued, s.demand_score, r.seed) stock
    WHERE s.id <= 3 OR
          (NOT s.discontinued AND pg_temp.catalog_draw(s.id, 'purchase-interest', r.seed, 10000)
                                  < 10 + s.demand_score * 2)
), events AS (
    SELECT e.*, slot, (e.product_id - 1) * 3 + slot AS order_id
    FROM eligible e CROSS JOIN LATERAL generate_series(1, e.attempts) slot
), proposed AS (
    SELECT e.*,
           1 + pg_temp.catalog_draw(order_id, 'buyer', seed, member_count) AS member_id,
           CASE WHEN product_id = 1 THEN (ARRAY[3, 2, 7])[slot]
                WHEN product_id IN (2, 3) THEN 2
                ELSE 1 + pg_temp.catalog_draw(order_id, 'requested-units', seed,
                                               2 + demand_score / 40)::integer END AS requested,
           CASE WHEN product_id = 1 THEN (ARRAY['CONFIRMED','CANCELLED','CREATED'])[slot]
                WHEN product_id IN (2, 3) THEN 'CONFIRMED'
                WHEN pg_temp.catalog_draw(order_id, 'cancel', seed, 100) < 8 THEN 'CANCELLED'
                WHEN slot = attempts AND pg_temp.catalog_draw(order_id, 'pending', seed, 100) < 6 THEN 'CREATED'
                ELSE 'CONFIRMED' END AS status,
           released_at + (as_of - released_at) *
               (((slot - 1) * 1000 + 1 + pg_temp.catalog_draw(order_id, 'purchase-time', seed, 999))
                / (attempts * 1000.0))::double precision AS slot_time
    FROM events e
), budget AS (
    SELECT p.*,
           COALESCE(sum(requested) FILTER (WHERE status <> 'CANCELLED')
               OVER (PARTITION BY product_id ORDER BY slot
                     ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS earlier_reservations
    FROM proposed p
), allocated AS (
    SELECT b.*, least(requested, greatest(0, available - earlier_reservations))::integer AS units
    FROM budget b
)
SELECT order_id, member_id, product_id, units AS quantity, price AS unit_price, status,
       CASE WHEN status = 'CREATED' THEN greatest(slot_time, released_at, as_of - interval '1 day')
            ELSE slot_time END AS created_at
FROM allocated
WHERE units > 0;

-- The last buyer can accept the available part of their requested quantity.
-- After stock is exhausted later events produce no order. Cancellations release
-- all reserved units before the next event. Sales totals use the actual orders.
WITH sales AS (
    SELECT product_id,
           COALESCE(sum(quantity) FILTER (WHERE status IN ('CREATED','CONFIRMED')), 0)::integer AS units,
           max(created_at) AS last_order_at
    FROM seed_catalog_orders
    GROUP BY product_id
), balances AS (
    SELECT s.*, stock.opening_stock + stock.received_stock - COALESCE(sales.units, 0) AS final_stock,
           greatest(s.updated_at, sales.last_order_at) AS last_change
    FROM seed_catalog_skus s
    CROSS JOIN catalog_seed_run r
    CROSS JOIN LATERAL pg_temp.catalog_supply(s.id, s.is_limited, s.discontinued, s.demand_score, r.seed) stock
    LEFT JOIN sales ON sales.product_id = s.id
)
INSERT INTO products (id, name, price_amount, stock_quantity, description, category,
                      brand, color, gender, status, popularity_score, created_at, updated_at)
SELECT id, concat_ws(' ', brand, lineup, gender, item, model_code, initcap(color),
                     CASE WHEN id <= 13 THEN 'StudioCapsule' END),
       price, final_stock, concat_ws(' ', brand, lineup, item, model_code, lower(color)),
       category, brand, color, gender,
       CASE WHEN id > 3 AND discontinued THEN 'DISCONTINUED'
            WHEN final_stock = 0 THEN 'OUT_OF_STOCK' ELSE 'ACTIVE' END,
       demand_score * demand_score, released_at, last_change
FROM balances;

INSERT INTO catalog_seed_inventory (product_id, model_code, lineup, is_limited, demand_score, opening_stock, received_stock)
SELECT s.id, s.model_code, s.lineup, s.is_limited, s.demand_score, stock.opening_stock, stock.received_stock
FROM seed_catalog_skus s
CROSS JOIN catalog_seed_run r
CROSS JOIN LATERAL pg_temp.catalog_supply(s.id, s.is_limited, s.discontinued, s.demand_score, r.seed) stock;

INSERT INTO orders (id, member_id, product_id, quantity, unit_price, status, total_amount,
                    created_at, idempotency_key)
SELECT order_id, member_id, product_id, quantity, unit_price, status, unit_price * quantity,
       created_at, 'catalog-order-' || order_id
FROM seed_catalog_orders;

SELECT setval(pg_get_serial_sequence('members', 'id'), max(id)) FROM members;
SELECT setval(pg_get_serial_sequence('products', 'id'), max(id)) FROM products;
SELECT setval(pg_get_serial_sequence('orders', 'id'), coalesce(max(id), 1), max(id) IS NOT NULL) FROM orders;

CREATE INDEX idx_products_created_at_id ON products (created_at DESC, id DESC);
CREATE INDEX idx_products_popularity_id ON products (popularity_score DESC, id DESC);
CREATE INDEX idx_products_filter_latest_id ON products (category, brand, gender, color, created_at DESC, id DESC);
CREATE INDEX idx_products_filter_popularity_id ON products (category, brand, gender, color, popularity_score DESC, id DESC);
CREATE UNIQUE INDEX uk_orders_member_idempotency_key ON orders (member_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ANALYZE members;
ANALYZE products;
ANALYZE orders;
ANALYZE catalog_seed_inventory;
