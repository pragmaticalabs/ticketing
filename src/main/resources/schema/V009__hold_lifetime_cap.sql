-- Bound the TOTAL lifetime of a hold.
--
-- V004/V007 made the claim guard admit a reclaim, and the guard now also admits the SAME customer's
-- own live hold -- which is what lets a hold become a purchase. The ON CONFLICT branch, however,
-- rewrote both `expires_at` (now() + 15 minutes) and `created_at` (now()) on every admitted claim, so
-- a client re-calling acquire-hold on a timer renewed its own hold indefinitely. Under on-sale
-- contention that is not a hold, it is a squat: the seat never returns to the pool.
--
-- The fix needs a start that survives a refresh. `created_at` cannot be it: the orphan reaper
-- (`state = 'confirmed' AND created_at < now() - interval '1 hour'`) reads `created_at` as the age of
-- the CURRENT claim, and freezing it across refreshes would make the reaper age a claim from the
-- first hold rather than from the claim it is reaping. So the start of the hold's lifetime gets its
-- own column, and `created_at` keeps its meaning untouched.
--
-- `held_since` is preserved only when the admitted row is the same customer's LIVE hold (a refresh);
-- every other admitted branch -- cancelled, expired, an expired hold, a different customer -- is a
-- genuine reclaim and starts a fresh lifetime from now(). The claim guard then refuses a refresh once
-- `now() - held_since` reaches the cap (60 minutes; see BookingStore.MAX_HOLD_MINUTES), while leaving
-- the reclaim branches untouched: the squatter's outstanding TTL simply runs out (at most 15 more
-- minutes) and the seat returns to contention through the expired branch or the hold sweep.

-- 1. The preserved start of the current hold's lifetime. Backfilled from `created_at`, which for
--    every existing row IS the start of its current claim.
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS held_since TIMESTAMPTZ;
UPDATE reservations SET held_since = created_at WHERE held_since IS NULL;
ALTER TABLE reservations ALTER COLUMN held_since SET NOT NULL;
ALTER TABLE reservations ALTER COLUMN held_since SET DEFAULT now();
