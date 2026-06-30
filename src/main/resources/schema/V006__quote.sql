-- Customer-facing quote read model (split, independently scaled). Owner: quote.
-- A projection of the latest price per scope, maintained by subscribing to PriceChanged.

CREATE TABLE IF NOT EXISTS price_view (
    scope_key     TEXT PRIMARY KEY,               -- event_id + ':' + tier (+ ':' + seat_id)
    event_id      UUID NOT NULL,
    seat_id       UUID,
    tier          TEXT NOT NULL,
    amount_minor  BIGINT NOT NULL,
    currency      TEXT NOT NULL,
    version       BIGINT NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_price_view_event ON price_view (event_id);
