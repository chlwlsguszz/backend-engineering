CREATE SEQUENCE product_search_outbox_revision_seq AS bigint NO CYCLE;

-- A deleted product must keep its pending search update until Elasticsearch acknowledges it.
CREATE TABLE product_search_outbox (
    product_id bigint PRIMARY KEY,
    revision bigint NOT NULL DEFAULT nextval('product_search_outbox_revision_seq'),
    available_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    lease_token uuid,
    retry_count integer NOT NULL DEFAULT 0 CHECK (retry_count >= 0)
);

ALTER SEQUENCE product_search_outbox_revision_seq OWNED BY product_search_outbox.revision;

CREATE INDEX idx_product_search_outbox_available ON product_search_outbox (available_at, product_id);
