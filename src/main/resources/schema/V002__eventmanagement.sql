-- Event-management: venue/seat structure + event lifecycle. Owner subsystem: eventmanagement.
-- current-state + audit-as-data. Seat status converges from booking's SeatSold/SeatReleased facts.

CREATE TABLE IF NOT EXISTS events (
    id          UUID PRIMARY KEY,
    venue       TEXT NOT NULL,
    on_sale_at  TEXT,                              -- ISO-8601 string (bound from a String param)
    status      TEXT NOT NULL DEFAULT 'draft'      -- 'draft' | 'on_sale' | 'cancelled'
);

CREATE TABLE IF NOT EXISTS seats (
    id        UUID PRIMARY KEY,
    event_id  UUID NOT NULL REFERENCES events (id),
    section   TEXT NOT NULL,
    seat_row  TEXT NOT NULL,
    number    INT  NOT NULL,
    tier      TEXT NOT NULL,
    state     TEXT NOT NULL DEFAULT 'available'    -- 'available' | 'blocked' | 'sold' | 'withdrawn'
);

CREATE INDEX IF NOT EXISTS idx_seats_event  ON seats (event_id);
CREATE INDEX IF NOT EXISTS idx_seats_state  ON seats (state);

CREATE TABLE IF NOT EXISTS event_audit (
    id        UUID PRIMARY KEY,
    event_id  UUID NOT NULL,
    kind      TEXT NOT NULL,
    at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail    TEXT
);
