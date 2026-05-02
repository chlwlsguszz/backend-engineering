CREATE INDEX idx_products_created_at_id ON products (created_at DESC, id DESC);

CREATE INDEX idx_products_popularity_id ON products (popularity_score DESC, id DESC);

CREATE INDEX idx_products_filter_latest_id
    ON products (category, brand, gender, color, created_at DESC, id DESC);

CREATE INDEX idx_products_filter_popularity_id
    ON products (category, brand, gender, color, popularity_score DESC, id DESC);