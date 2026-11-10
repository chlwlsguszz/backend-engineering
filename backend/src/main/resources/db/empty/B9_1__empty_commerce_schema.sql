-- Opt-in schema-only baseline for new databases. B9 remains the reproducible benchmark fixture.
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
CREATE TABLE catalog_seed_inventory (
    product_id bigint PRIMARY KEY REFERENCES products (id) ON DELETE CASCADE,
    model_code text NOT NULL,
    lineup text NOT NULL,
    is_limited boolean NOT NULL,
    demand_score integer NOT NULL CHECK (demand_score BETWEEN 1 AND 100),
    opening_stock integer NOT NULL CHECK (opening_stock >= 0),
    received_stock integer NOT NULL CHECK (received_stock >= 0)
);

CREATE INDEX idx_products_created_at_id ON products (created_at DESC, id DESC);
CREATE INDEX idx_products_popularity_id ON products (popularity_score DESC, id DESC);
CREATE INDEX idx_products_filter_latest_id ON products (category, brand, gender, color, created_at DESC, id DESC);
CREATE INDEX idx_products_filter_popularity_id ON products (category, brand, gender, color, popularity_score DESC, id DESC);
CREATE UNIQUE INDEX uk_orders_member_idempotency_key ON orders (member_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
