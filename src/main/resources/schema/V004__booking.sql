-- Booking: reservations/holds, bookings, payments, tickets, audit. Owner: booking.
-- Design-out double-booking: ONE reservation row per seat (unique seat_id). The claim is a single
-- INSERT ... ON CONFLICT (seat_id) DO UPDATE ... WHERE (existing is cancelled/expired/expired-hold),
-- so a fresh hold or a confirmed booking cannot be overwritten (losing buyer gets zero rows ->
-- typed SeatUnavailable), while a stale/expired hold is reclaimed atomically. No CTE, no race.

CREATE TABLE IF NOT EXISTS reservations (
    id           UUID PRIMARY KEY,
    seat_id      UUID NOT NULL,
    event_id     UUID NOT NULL,
    customer_id  UUID NOT NULL,
    state        TEXT NOT NULL,                    -- 'held' | 'confirmed' | 'cancelled' | 'expired'
    expires_at   TIMESTAMPTZ,                      -- hold decay boundary (FER); NULL once confirmed
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The design-out serialization point: exactly one reservation row per seat.
CREATE UNIQUE INDEX IF NOT EXISTS uq_reservation_seat ON reservations (seat_id);

CREATE INDEX IF NOT EXISTS idx_reservations_expiry ON reservations (expires_at) WHERE state = 'held';

CREATE TABLE IF NOT EXISTS bookings (
    id              UUID PRIMARY KEY,
    reservation_id  UUID NOT NULL REFERENCES reservations (id),
    seat_id         UUID NOT NULL,
    event_id        UUID NOT NULL,
    customer_id     UUID NOT NULL,
    status          TEXT NOT NULL,                 -- 'confirmed' | 'cancelled'
    ticket_id       UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS payments (
    id            UUID PRIMARY KEY,
    booking_id    UUID NOT NULL,
    status        TEXT NOT NULL,                   -- 'authorized' | 'captured' | 'voided' | 'refunded'
    receipt_id    UUID,
    amount_minor  BIGINT NOT NULL,
    currency      TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS tickets (
    id          UUID PRIMARY KEY,
    booking_id  UUID NOT NULL,
    seat_id     UUID NOT NULL,
    status      TEXT NOT NULL                      -- 'issued' | 'invalidated'
);

CREATE TABLE IF NOT EXISTS booking_audit (
    id          UUID PRIMARY KEY,
    booking_id  UUID,
    kind        TEXT NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail      TEXT
);
