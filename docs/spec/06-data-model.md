# Data Model

Persistent state, every constraint, and the invariant each one defends.

The reference uses a relational database. **That is not binding** — but several guarantees depend on
capabilities described here, and §7 states exactly which ones a substitute store must provide.

Column names below are the reference's; an implementation may name things anything.

---

## 1. Events

| Field | Type | Notes |
|---|---|---|
| id | UUID | primary key |
| venue | text | not null |
| on_sale_at | text | **nullable**, ISO-8601 string |
| status | text | not null, default `draft` — one of `draft`, `on_sale`, `cancelled` |

The on-sale time is stored as a **string**, not a timestamp. It is validated as ISO-8601 on the way
in. An implementation MAY use a proper timestamp type; if it does, it must still render a missing
value as an empty string on the wire (`03-processes-eventmanagement.md` §E8).

---

## 2. Seats

| Field | Type | Notes |
|---|---|---|
| id | UUID | primary key |
| event_id | UUID | not null, **references events** |
| section, seat_row | text | not null |
| number | int | not null |
| tier | text | not null |
| state | text | not null, default `available` |
| version | bigint | not null, default 0 — mirrors the applied fact's version |

Indexed by event and by state.

**This is the only cross-subsystem foreign key in the entire model.** Booking never references event
management's tables; the two are linked solely through the sellability process. That asymmetry is
deliberate — see §4.

---

## 3. Reservations — the contention point

| Field | Type | Notes |
|---|---|---|
| **seat_id** | UUID | **primary key** — the serialization point |
| claim_id | UUID | not null, **rotates on every successful claim** |
| event_id, customer_id | UUID | not null |
| state | text | not null — `held`, `confirmed`, `cancelled`, `expired` |
| expires_at | timestamptz | nullable — null once confirmed |
| created_at | timestamptz | not null — dates the **current** claim |
| held_since | timestamptz | not null — start of the **current hold's lifetime** |
| version | bigint | not null, default 0 — bumped on **every** transition |

### Constraints and what they defend

| Constraint | Defends |
|---|---|
| `seat_id` as primary key | **The central invariant**: exactly one reservation per seat, ever. The row lock on this key is what serializes contention. |
| partial index on `expires_at` where state is `held` | scopes the expiry scan to live holds |
| partial index on `created_at` where state is `confirmed` | scopes the orphan reap |

**Three timestamps that are not interchangeable:**

- `created_at` dates the **current claim** and moves on every claim. The orphan reaper ages on it.
- `held_since` is the **current hold's lifetime origin**. Preserved across a same-customer refresh;
  reset on every genuine reclaim. The 60-minute cap measures from it.
- `expires_at` is the current hold's **deadline**. Rewritten on every refresh.

Collapsing any two of these breaks something: sharing `created_at` and `held_since` reintroduces
indefinite squatting; sharing `held_since` and `expires_at` makes refresh impossible.

---

## 4. Bookings

| Field | Type | Notes |
|---|---|---|
| id | UUID | primary key |
| reservation_claim_id | UUID | not null — **no foreign key**, deliberately |
| seat_id, event_id, customer_id | UUID | not null |
| status | text | not null — `confirmed`, `cancelled` |
| ticket_id | UUID | nullable |
| created_at | timestamptz | not null |

Indexed by claim identifier.

> **The absent foreign key is the fix for a real bug.** See `01-domain-model.md` §4. A sale records
> the claim it was made under as a **historical fact**; the reservation row it names will be
> reclaimed and its claim identifier rotated away. A foreign key here makes reclaiming a
> previously-booked seat impossible, permanently breaking resale and hold-to-purchase conversion.

---

## 5. Payments and tickets

**Payments**: id, booking_id (not null), status (`authorized`, `captured`, `voided`, `refunded`),
receipt_id (nullable), amount_minor, currency. Indexed by booking.

That index supports the **refund-idempotency read** — the lookup that stops a re-driven cancellation
from refunding twice (`02-processes-booking.md` §B4).

The refund write is **guarded on status `authorized`**, so a payment can only move forward once.

**Tickets**: id, booking_id, seat_id, status (`issued`, `invalidated`). Invalidation is
unconditional and therefore idempotent.

Neither table references bookings by foreign key — which is what makes the write ordering in
`02-processes-booking.md` §B3 safe.

---

## 6. Derived state

These belong to derived subsystems. **They are copies. Losing them entirely should cost only
rebuild time**, not correctness — though under at-most-once delivery the reference **cannot** rebuild
them, which is itself a finding (§8).

**Seat availability**: seat_id (primary key), event_id, state (**only `available` or `sold`**),
version, updated_at. Indexed by event. Carries a hold-expiry column that **no query references** —
dead.

**Price history** (append-only, authoritative): id, event_id, seat_id (nullable — null means
tier-wide), tier, amount_minor, currency, version, created_at.
**Unique on (event_id, tier, version)** — this is what defeats concurrent double-allocation
(`04-processes-pricing-quote.md`).

**Current price** (pricing's own projection) and **price view** (the quote subsystem's projection):
identical shape — scope_key (primary key), event_id, seat_id, tier, amount_minor, currency, version,
updated_at.

**They are separate tables on purpose.** One is written synchronously by pricing; the other
asynchronously from facts. Merging them collapses the distinction that `04-processes-pricing-quote.md`
§P3/P4 rests on.

---

## 7. What a substitute store MUST provide

The relational model is not binding; these capabilities are.

1. **A conditional atomic write keyed by seat**, admitting or refusing in one operation based on a
   predicate over the current value, and reporting which happened. Everything in
   `09-guarantees.md` §1 rests on this. Compare-and-set is sufficient.
2. **A uniqueness constraint** that makes a concurrent duplicate version allocation fail visibly.
3. **Conditional updates that report whether they matched**, for every guarded state transition. A
   write that cannot distinguish "changed nothing" from "succeeded" is unusable here — see §7.1.
4. **Single-statement read-and-write** for the orphan reap, so it cannot interleave with a purchase.
5. **Durability** of authoritative state. Derived state may be rebuildable.

### 7.1 The uniform transition rule

**Every state-changing write in the authoritative subsystems is guarded and returns whether it
matched.** An out-of-state transition matches nothing and reports so, rather than silently
succeeding.

This is deliberate uniformity — the reference calls an unguarded transition in these stores *a
defect*, because a write that returns nothing reports a no-op as success, and a caller then believes
it changed state it did not change.

**Corollary an implementation must accept:** the guard is authoritative but **not
self-describing** — it says *that* it refused, never *why*. Any process needing the reason issues a
**separate, non-atomic follow-up read**, whose answer may already be stale. That is why *transition
raced* exists as an outcome (`03-processes-eventmanagement.md` §E2).

---

## 8. Schema evolution — three fixes worth inheriting

The reference's migration history contains three corrections that a fresh implementation should
simply build in.

**Seat as reservation identity.** Originally a surrogate reservation key rotated in place while
bookings held a foreign key to it. Once a seat had been booked, reclaiming it violated that key —
a cancelled seat could never be resold and a hold could never become a purchase. Fixed by making the
**never-changing seat** the key, moving the rotating identity to a non-key column, and dropping the
foreign key.

**Per-seat fact versions.** Price facts already carried a version enabling monotonic upserts; seat
facts did not. Because seat state **cycles**, unconditional last-write-wins was corruptible by
redelivery or reordering — **reachable with no node failure at all**, purely from consumer retries
racing the delivery loop. Fixed by versioning every seat transition and guarding both consumers.

**Hold lifetime cap.** The claim guard rewrote the hold's timestamps on every refresh, so a client on
a timer could renew forever. Fixed by adding a preserved lifetime origin and refusing refreshes past
the cap.

> **All three are concurrency defects that passed ordinary testing.** They are the parts of this
> design most likely to be lost in a port, because each looks like an arbitrary modelling choice
> until you know the bug it closes.

---

## 9. What the reference does not verify

**Its own queries are only partly checked against its schema.** The build-time validator's
output-column check engages only for statements of a particular shape; measured on this codebase,
**12 of 14 write statements returning columns were never output-validated**, including the seat-claim
statement §3 rests on. Table names and assigned columns are checked; returned columns and filter
columns in those statements are not.

**Do not treat the reference's queries as schema-verified.** An implementation should test its own
data access against a real schema, which will leave it better verified than the reference in this
respect.
