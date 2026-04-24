ALTER TABLE products
    ADD COLUMN description TEXT NOT NULL DEFAULT '',
    ADD COLUMN category VARCHAR(100) NOT NULL DEFAULT 'ETC',
    ADD COLUMN brand VARCHAR(100) NOT NULL DEFAULT 'GENERIC',
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN popularity_score INT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- Make seed data distribution more meaningful for search and detail experiments.
UPDATE products
SET
    category = CASE (id % 6)
        WHEN 0 THEN 'BOOKS'
        WHEN 1 THEN 'ELECTRONICS'
        WHEN 2 THEN 'HOME'
        WHEN 3 THEN 'FASHION'
        WHEN 4 THEN 'FOOD'
        ELSE 'SPORTS'
    END,
    brand = CASE (id % 10)
        WHEN 0 THEN 'ACME'
        WHEN 1 THEN 'NOVA'
        WHEN 2 THEN 'HYPER'
        WHEN 3 THEN 'ORBIT'
        WHEN 4 THEN 'ZEN'
        WHEN 5 THEN 'PRIME'
        WHEN 6 THEN 'LUMEN'
        WHEN 7 THEN 'PULSE'
        WHEN 8 THEN 'AERO'
        ELSE 'CORE'
    END,
    description = 'Detailed description for Product ' || id,
    status = CASE
        WHEN stock_quantity = 0 THEN 'OUT_OF_STOCK'
        WHEN id % 37 = 0 THEN 'DISCONTINUED'
        ELSE 'ACTIVE'
    END,
    popularity_score = CASE
        WHEN id <= 50 THEN 1000 - (id * 10)   -- hot products (traffic skew)
        WHEN id <= 200 THEN 300 - id
        ELSE floor(random() * 120)::int
    END,
    updated_at = CURRENT_TIMESTAMP - make_interval(days => (id % 30));
