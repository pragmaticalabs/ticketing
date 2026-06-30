-- Availability read model (split, independently scaled). Owner: availability.
-- A bounded-staleness projection maintained by subscribing to SeatSold / SeatReleased.

CREATE TABLE IF NOT EXISTS seat_availability (
    seat_id          UUID PRIMARY KEY,
    event_id         UUID NOT NULL,
    state            TEXT NOT NULL,               -- SeatState.dbValue(); projected subset: 'available' | 'sold'
    hold_expires_at  TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_seat_availability_event ON seat_availability (event_id);
