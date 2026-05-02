CREATE INDEX idx_products_created_at_id ON products (created_at DESC, id DESC);

CREATE INDEX idx_products_popularity_id ON products (popularity_score DESC, id DESC);