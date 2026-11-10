-- Existing pending work is observable from the instant this migration is installed.
ALTER TABLE product_search_outbox ADD COLUMN enqueued_at timestamptz NOT NULL DEFAULT now();
