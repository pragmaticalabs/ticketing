-- Booking, revision 2: the seat IS the reservation's identity.
--
-- V004 made `id` the primary key and rotated it in place on every reclaim
-- (`ON CONFLICT (seat_id) DO UPDATE SET id = EXCLUDED.id`), while `bookings.reservation_id`
-- referenced it with no ON UPDATE CASCADE. Once a seat had ever been booked, reclaiming it raised a
-- foreign-key violation, so a cancelled seat could never be re-sold.
--
-- The fix follows V004's own design comment to its conclusion: the serialization point is "exactly
-- one reservation row per seat", so `seat_id` becomes the primary key -- a key that never changes.
-- The rotating part of a claim moves into `claim_id`, an ordinary NON-key column regenerated on
-- every successful claim, which is what the slices carry to confirm or release the claim they made.
-- `bookings` records the claim it was sold under as a point-in-time fact (`reservation_claim_id`,
-- no FK): a claim is history, not a live row, so a later reclaim of the same seat cannot invalidate
-- the bookings that recorded its previous claims.
--
-- Existing rows keep their identity: the old `id` becomes the initial `claim_id`, so any booking
-- already referencing it still names the claim it was sold under.
--
-- On the [PG002] "CREATE INDEX without CONCURRENTLY" advisories this file raises: they are accepted
-- deliberately, not overlooked. Aether classifies a migration file Flyway-style -- a single
-- non-transactional statement forces the WHOLE file into autocommit
-- (AetherSchemaManager "per-file classification", MigrationDialects ddlTransactional). Because
-- CREATE INDEX CONCURRENTLY cannot run inside a transaction, using it here would drop this
-- migration's atomicity -- and this file drops a primary key and re-keys a table, which must not be
-- able to half-apply. A brief write lock is the cheaper failure mode than a partially re-keyed
-- `reservations`. The same reasoning does NOT extend to a future migration that only adds an index
-- to a live, heavily-written table; that one should use CONCURRENTLY and stand alone in its own file.
--
-- This migration is versioned, not re-runnable: the `ALTER TABLE ... RENAME COLUMN` in step 5 has no
-- IF EXISTS form in PostgreSQL. Aether/Flyway applies each version exactly once, so the surrounding
-- IF EXISTS / IF NOT EXISTS guards make a partial re-run safe up to that point.

-- 1. The rotating, NON-key claim identity. Backfilled from the old primary key.
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS claim_id UUID;
UPDATE reservations SET claim_id = id WHERE claim_id IS NULL;
ALTER TABLE reservations ALTER COLUMN claim_id SET NOT NULL;

-- 2. The seat becomes the key. Dropping `id` also drops the primary key defined on it; the explicit
--    constraint drop above it is belt-and-braces for installations where that key was named.
ALTER TABLE reservations DROP CONSTRAINT IF EXISTS reservations_pkey;
ALTER TABLE reservations DROP COLUMN IF EXISTS id;
ALTER TABLE reservations ADD PRIMARY KEY (seat_id);

-- 3. Redundant now that seat_id is the primary key.
DROP INDEX IF EXISTS uq_reservation_seat;

-- 4. Supports the orphan reaper: confirmed reservations old enough to have lost their purchase.
CREATE INDEX IF NOT EXISTS idx_reservations_orphan ON reservations (created_at) WHERE state = 'confirmed';

-- 5. A booking records the claim it was sold under as a historical fact. Dropping the foreign key is
--    the point: rotating a seat's claim must never be blocked by the history of its past sales.
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_reservation_id_fkey;
ALTER TABLE bookings RENAME COLUMN reservation_id TO reservation_claim_id;

CREATE INDEX IF NOT EXISTS idx_bookings_claim ON bookings (reservation_claim_id);

-- 6. The refund idempotency read in cancel-ticket is keyed by booking.
CREATE INDEX IF NOT EXISTS idx_payments_booking ON payments (booking_id);
