-- Add 100,000 apparel products for meaningful search/load testing.
-- Naming rule: brand + gender + category-specific item name + color
-- Price rule: 10,000 ~ 400,000, step 100 (mid-price skew: 40,000 ~ 180,000)

WITH generated AS (
    SELECT
        gs,
        ((mod(hashtextextended('brand-' || gs::text, 0), 20) + 20) % 20)::int AS brand_idx,
        ((mod(hashtextextended('gender-' || gs::text, 0), 3) + 3) % 3)::int AS gender_idx,
        ((mod(hashtextextended('category-' || gs::text, 0), 6) + 6) % 6)::int AS category_idx,
        ((mod(hashtextextended('color-' || gs::text, 0), 8) + 8) % 8)::int AS color_idx,
        ((mod(hashtextextended('item-' || gs::text, 0), 5) + 5) % 5)::int AS item_variant_idx,
        CASE ((mod(hashtextextended('brand-' || gs::text, 0), 20) + 20) % 20)::int
            WHEN 0 THEN 'Nike'
            WHEN 1 THEN 'Adidas'
            WHEN 2 THEN 'Puma'
            WHEN 3 THEN 'New Balance'
            WHEN 4 THEN 'Under Armour'
            WHEN 5 THEN 'Converse'
            WHEN 6 THEN 'Reebok'
            WHEN 7 THEN 'Fila'
            WHEN 8 THEN 'Asics'
            WHEN 9 THEN 'Lululemon'
            WHEN 10 THEN 'Jordan'
            WHEN 11 THEN 'Vans'
            WHEN 12 THEN 'Skechers'
            WHEN 13 THEN 'Champion'
            WHEN 14 THEN 'Levis'
            WHEN 15 THEN 'Patagonia'
            WHEN 16 THEN 'The North Face'
            WHEN 17 THEN 'Columbia'
            WHEN 18 THEN 'Oakley'
            ELSE 'Carhartt'
        END AS brand,
        CASE ((mod(hashtextextended('gender-' || gs::text, 0), 3) + 3) % 3)::int
            WHEN 0 THEN 'MEN'
            WHEN 1 THEN 'WOMEN'
            ELSE 'UNISEX'
        END AS gender,
        CASE ((mod(hashtextextended('category-' || gs::text, 0), 6) + 6) % 6)::int
            WHEN 0 THEN 'TOP'
            WHEN 1 THEN 'BOTTOM'
            WHEN 2 THEN 'OUTER'
            WHEN 3 THEN 'SHOES'
            WHEN 4 THEN 'GLASSES'
            ELSE 'HAT'
        END AS category,
        CASE ((mod(hashtextextended('color-' || gs::text, 0), 8) + 8) % 8)::int
            WHEN 0 THEN 'BLACK'
            WHEN 1 THEN 'WHITE'
            WHEN 2 THEN 'NAVY'
            WHEN 3 THEN 'GRAY'
            WHEN 4 THEN 'BEIGE'
            WHEN 5 THEN 'RED'
            WHEN 6 THEN 'BLUE'
            ELSE 'GREEN'
        END AS color
    FROM generate_series(1, 100000) AS gs
)
INSERT INTO products (
    name,
    price_amount,
    stock_quantity,
    description,
    category,
    brand,
    color,
    gender,
    status,
    popularity_score,
    updated_at
)
SELECT
    g.brand || ' ' || g.gender || ' ' ||
    CASE g.category
        WHEN 'TOP' THEN
            CASE g.item_variant_idx
                WHEN 0 THEN 'T-Shirt'
                WHEN 1 THEN 'Long Sleeve'
                WHEN 2 THEN 'Hoodie'
                WHEN 3 THEN 'Sweatshirt'
                ELSE 'Knit'
            END
        WHEN 'BOTTOM' THEN
            CASE g.item_variant_idx
                WHEN 0 THEN 'Jeans'
                WHEN 1 THEN 'Slacks'
                WHEN 2 THEN 'Jogger Pants'
                WHEN 3 THEN 'Shorts'
                ELSE 'Cargo Pants'
            END
        WHEN 'OUTER' THEN
            CASE g.item_variant_idx
                WHEN 0 THEN 'Jacket'
                WHEN 1 THEN 'Jumper'
                WHEN 2 THEN 'Coat'
                WHEN 3 THEN 'Cardigan'
                ELSE 'Windbreaker'
            END
        WHEN 'SHOES' THEN
            CASE g.item_variant_idx
                WHEN 0 THEN 'Sneakers'
                WHEN 1 THEN 'Running Shoes'
                WHEN 2 THEN 'Loafers'
                WHEN 3 THEN 'Boots'
                ELSE 'Sandals'
            END
        WHEN 'GLASSES' THEN
            CASE g.item_variant_idx
                WHEN 0 THEN 'Round Glasses'
                WHEN 1 THEN 'Square Glasses'
                WHEN 2 THEN 'Sunglasses'
                WHEN 3 THEN 'Metal Frame'
                ELSE 'Horn Rim'
            END
        ELSE
            CASE g.item_variant_idx
                WHEN 0 THEN 'Baseball Cap'
                WHEN 1 THEN 'Bucket Hat'
                WHEN 2 THEN 'Beanie'
                WHEN 3 THEN 'Visor'
                ELSE 'Camp Cap'
            END
    END || ' ' || initcap(lower(g.color)) AS name,
    (
        CASE
            WHEN random() < 0.70 THEN (40000 + (floor(random() * 1401)::int * 100))
            ELSE (10000 + (floor(random() * 3901)::int * 100))
        END
    )::numeric(19, 4) AS price_amount,
    floor(random() * 300)::int AS stock_quantity,
    'Performance ' || lower(g.category) || ' product in ' || lower(g.color) || ' color' AS description,
    g.category,
    g.brand,
    g.color,
    g.gender,
    CASE
        WHEN random() < 0.88 THEN 'ACTIVE'
        WHEN random() < 0.96 THEN 'OUT_OF_STOCK'
        ELSE 'DISCONTINUED'
    END AS status,
    CASE
        WHEN g.gs <= 5000 THEN 5000 - g.gs  -- intentional popularity skew for experiments
        ELSE floor(random() * 1200)::int
    END AS popularity_score,
    CURRENT_TIMESTAMP - make_interval(days => (g.gs % 90)::int) AS updated_at
FROM generated g;
