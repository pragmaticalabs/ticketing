-- Per-seat fact ordering.
--
-- `PriceChanged` already carries a `version`, which is what lets `PricingStore.upsertCurrent` guard
-- with `WHERE current_price.version < EXCLUDED.version` and be correct under reordering or
-- redelivery. `SeatSold` and `SeatReleased` carried none, so `SeatProjectionStore.upsertStatus` was
-- an unconditional last-write-wins upsert. Seat state is NOT terminal -- a seat goes
-- available -> sold -> available across a cancellation -- so a redelivered or overtaking fact
-- silently corrupts the read model. That is reachable without any node failure: the consumer push
-- path has no in-flight guard and a retry can race the poll loop.
--
-- The version source is the reservation slot from V007. `reservations` is keyed by `seat_id`, one row
-- per seat, and EVERY seat lifecycle transition (claim, confirm, release, cancel, expire,
-- orphan-reap) passes through that single row. A counter on it is therefore a correct per-seat
-- sequence -- the same shape pricing already uses, sourced from the serialization point the booking
-- design already established rather than from a new one.

-- 1. The sequence itself. Bumped by every state-changing statement in BookingStore and returned, so
--    the publisher stamps the fact with the version of the transition that produced it.
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- 2. Ordering guards on the two projections that consume the seat facts. Defaulting to 0 means rows
--    written before this migration accept the next fact of any version, which is the desired
--    behaviour for a projection that is rebuilt from facts anyway.
ALTER TABLE seat_availability ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- 3. Authoritative seat state carries the version too, so a stale convergence fact cannot move a seat
--    backwards. This guards ordering; it does NOT replace the state predicates already on
--    markSeatSold / markSeatAvailable -- both apply.
ALTER TABLE seats ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
