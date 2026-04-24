ALTER TABLE products
    ADD COLUMN description TEXT NOT NULL DEFAULT '',
    ADD COLUMN category VARCHAR(100) NOT NULL DEFAULT 'TOP',
    ADD COLUMN brand VARCHAR(100) NOT NULL DEFAULT 'NIKE',
    ADD COLUMN color VARCHAR(50) NOT NULL DEFAULT 'BLACK',
    ADD COLUMN gender VARCHAR(20) NOT NULL DEFAULT 'UNISEX',
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN popularity_score INT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- Make seed data distribution more meaningful for search and detail experiments.
UPDATE products
SET
    category = CASE (id % 6)
        WHEN 0 THEN 'TOP'
        WHEN 1 THEN 'BOTTOM'
        WHEN 2 THEN 'OUTER'
        WHEN 3 THEN 'SHOES'
        WHEN 4 THEN 'GLASSES'
        ELSE 'HAT'
    END,
    brand = CASE (id % 10)
        WHEN 0 THEN 'Nike'
        WHEN 1 THEN 'Adidas'
        WHEN 2 THEN 'Puma'
        WHEN 3 THEN 'New Balance'
        WHEN 4 THEN 'Under Armour'
        WHEN 5 THEN 'Converse'
        WHEN 6 THEN 'Reebok'
        WHEN 7 THEN 'Fila'
        WHEN 8 THEN 'Asics'
        ELSE 'Lululemon'
    END,
    color = CASE (id % 8)
        WHEN 0 THEN 'BLACK'
        WHEN 1 THEN 'WHITE'
        WHEN 2 THEN 'NAVY'
        WHEN 3 THEN 'GRAY'
        WHEN 4 THEN 'BEIGE'
        WHEN 5 THEN 'RED'
        WHEN 6 THEN 'BLUE'
        ELSE 'GREEN'
    END,
    gender = CASE (id % 3)
        WHEN 0 THEN 'MEN'
        WHEN 1 THEN 'WOMEN'
        ELSE 'UNISEX'
    END,
    description = 'Comfort fit ' || lower(replace(category, '_', ' ')) || ' from ' || brand,
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
    updated_at = CURRENT_TIMESTAMP - make_interval(days => (id % 30)::int);
