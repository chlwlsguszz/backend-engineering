CREATE TABLE members (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price_amount NUMERIC(19, 4) NOT NULL CHECK (price_amount >= 0),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE inventories (
    product_id BIGINT PRIMARY KEY,
    quantity INT NOT NULL CHECK (quantity >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inventories_product
        FOREIGN KEY (product_id)
            REFERENCES products (id)
            ON DELETE CASCADE
);

CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_amount NUMERIC(19, 4) NOT NULL CHECK (total_amount >= 0),
    idempotency_key VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_orders_member
        FOREIGN KEY (member_id)
            REFERENCES members (id)
);

CREATE UNIQUE INDEX ux_orders_idempotency_key_not_null
    ON orders (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_orders_member_id ON orders (member_id);
CREATE INDEX ix_orders_created_at ON orders (created_at);

CREATE TABLE order_lines (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    unit_price NUMERIC(19, 4) NOT NULL CHECK (unit_price >= 0),
    quantity INT NOT NULL CHECK (quantity > 0),
    line_total NUMERIC(19, 4) NOT NULL CHECK (line_total >= 0),
    CONSTRAINT fk_order_lines_order
        FOREIGN KEY (order_id)
            REFERENCES orders (id)
            ON DELETE CASCADE,
    CONSTRAINT fk_order_lines_product
        FOREIGN KEY (product_id)
            REFERENCES products (id)
);

CREATE INDEX ix_order_lines_order_id ON order_lines (order_id);
