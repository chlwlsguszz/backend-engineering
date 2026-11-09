-- V2 inserted 500 products; V4 inserted the next 10,000,000 in one statement.
-- Identify that statement's timestamp using V4's generated description and
-- backdated updated_at. A scalar lookup below skips a missing batch and fails
-- on ambiguous batches rather than picking an arbitrary timestamp.
WITH seed_batches AS (
    SELECT created_at
    FROM products
    WHERE id BETWEEN 501 AND 10000500
      AND description = 'Performance ' || lower(category) || ' product in ' || lower(color)
      AND updated_at < created_at
    GROUP BY created_at
    HAVING count(*) > 1
)
UPDATE products AS p
SET (created_at, updated_at) = (
    SELECT dates.created_at, GREATEST(p.updated_at, dates.created_at)
    FROM (VALUES (
        -- Deterministic offset in [0, 365 days), at microsecond resolution.
        p.created_at - interval '1 microsecond' *
            ((hashtextextended(p.id::text, 42) & 9223372036854775807) % 31536000000000)
    )) AS dates(created_at)
)
WHERE p.id BETWEEN 501 AND 10000500
  AND p.created_at = (SELECT created_at FROM seed_batches);

ANALYZE products (created_at, updated_at);
