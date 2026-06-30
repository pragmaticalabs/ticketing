-- Pricing: append-only price history (design-out) + a current_price projection. Owner: pricing.
-- Correction is a new appended row with a higher version, never an overwrite of history.

CREATE TABLE IF NOT EXISTS price_events (
    id            UUID PRIMARY KEY,
    event_id      UUID NOT NULL,
    seat_id       UUID,                            -- NULL = tier-wide price
    tier          TEXT NOT NULL,
    amount_minor  BIGINT NOT NULL,
    currency      TEXT NOT NULL,
    version       BIGINT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- UNIQUE so a version is allocated at most once per (event, tier): a concurrent double-allocation
-- (two writers both reading the same max(version)) makes the loser's INSERT fail visibly instead of
-- silently appending a duplicate-version row. Also serves as the scope lookup index.
CREATE UNIQUE INDEX IF NOT EXISTS uq_price_events_scope_version ON price_events (event_id, tier, version);

CREATE TABLE IF NOT EXISTS current_price (
    scope_key     TEXT PRIMARY KEY,                -- event_id + ':' + tier (+ ':' + seat_id)
    event_id      UUID NOT NULL,
    seat_id       UUID,
    tier          TEXT NOT NULL,
    amount_minor  BIGINT NOT NULL,
    currency      TEXT NOT NULL,
    version       BIGINT NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
