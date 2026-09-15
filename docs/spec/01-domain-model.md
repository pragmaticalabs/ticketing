# Domain Model

Entities, value objects, invariants, and the two state machines. Everything here is **binding**; the
representation is not.

---

## 1. Value objects — parse, don't validate

Every external input is converted into a domain type **once, at the boundary**, and is either valid
from then on or never constructed at all. No process accepts a raw string and re-checks it later.

**This is binding as a property**, not as a technique: an implementation MUST reject malformed input
at the edge and MUST NOT carry unvalidated values into business logic. Whether it uses newtypes,
branded types, smart constructors, or classes with private constructors is free.

### 1.1 Identifiers

Eight identifier types — **seat, event, event-schedule, hold, booking, customer, receipt, ticket** —
are each a UUID wrapper with identical rules:

| Rule | Failure | Message |
|---|---|---|
| must not be blank | blank | `<Noun> id must not be blank` |
| must parse as a UUID | malformed | `<Noun> id must be a valid UUID` |

Both map to **HTTP 400**. They are distinct types: a seat identifier MUST NOT be accepted where an
event identifier is expected. In a language without cheap newtypes this may be enforced by
convention plus tests, but the distinctness is part of the design — several failures in this system
exist only because these are not interchangeable.

### 1.2 Money

Representation: an integer **minor-unit** amount plus a currency. Never a floating-point type.

- **Currency** is a closed set: `USD`, `EUR`, `GBP`. Parsed case-insensitively after trimming.
  Anything else fails as *unknown currency* → **422**.
- **Scale is fixed at 2** for all three currencies. This is hardcoded, not currency-aware — an
  implementation adding a zero-decimal or three-decimal currency MUST revisit it.
- **Parsing** an amount: decimal parse, then shift two places to an exact integer. A value needing
  more than two fractional digits (`10.005`) fails as *malformed amount* → **400**. Note it is
  malformed, not "too precise" — there is no separate failure for excess precision.
- **Non-negativity** is enforced at every construction boundary; a negative amount fails as
  *negative amount* → **422**.
- **Rounding** appears in exactly one place: percentage scaling computes
  `amountMinor × percent ÷ 100` at scale 0 with **half-up** rounding. Since amounts are
  non-negative, half-up means ties round away from zero. An implementation MUST use this rule —
  banker's rounding would produce different prices.
- **Currency mismatch** is defined for addition of two amounts. *Note:* addition is never invoked
  anywhere in pricing or quoting, so this failure is unreachable on those paths.

### 1.3 Percent

A strictly positive integer. `100` is identity, `>100` increases, `0 < x < 100` decreases.
Zero and negatives are rejected as *non-positive* → **422**.

**This is a design-out, not a convenience check.** Because a percentage can never be zero or
negative, scaling can never produce a negative or zeroed price, so `Money`'s non-negativity needs no
re-check on the result. An implementation that allows a zero or negative percentage reintroduces a
class of failure this design removes.

### 1.4 Seat location

Section (non-blank), row (non-blank), and a **strictly positive** seat number.

Note the deliberate status split: blank section or row is *malformed* → **400**, while a
non-positive number is *well-formed but unacceptable* → **422**. An implementation MUST preserve
that distinction — it is the general rule in §4 of `08-failure-catalog.md`, visible here in
miniature.

### 1.5 Price tier

Closed set: `PREMIUM`, `STANDARD`, `ECONOMY`, `ACCESSIBLE`, `RESTRICTED_VIEW`. Parsed
case-insensitively after trimming; an unrecognized token is *well-formed but unacceptable* → **422**.

---

## 2. The event lifecycle state machine

Three states. **Cancelled is terminal.**

```
   DRAFT ──────────────► ON_SALE
     │                      │
     └──────────┬───────────┘
                ▼
            CANCELLED   (terminal)
```

| Transition | Performed by | Admitted when | Refused with |
|---|---|---|---|
| `DRAFT → ON_SALE` | open-event | status is draft | already-open (409) from on-sale; event-cancelled (409) from cancelled |
| `DRAFT → CANCELLED` | cancel-event | status is not cancelled | — |
| `ON_SALE → CANCELLED` | cancel-event | status is not cancelled | — |

**Cancelling an already-cancelled event is reported as SUCCESS, not a failure.** The postcondition
is already satisfied. This is deliberate and an implementation MUST preserve it — it makes
cancellation safely retryable. Contrast with opening an already-open event, which **is** a failure,
because "already open" is not the postcondition the caller asked for.

**Cancelling an event does not cascade.** It does not release or withdraw seats, does not cancel or
refund bookings, and publishes no fact. This is a deliberate boundary: event management does not
reach into booking. An implementation MUST NOT add a cascade without recognizing it as a change to
the system's design, not a bug fix.

**Adding a seat reads but does not transition status:** refused only when the event is cancelled;
both draft and on-sale accept new seats.

---

## 3. The seat state machine

Four states, and — importantly — **it is not acyclic**. A seat sold and then released returns to
available. Every ordering guarantee in this system exists because of that cycle.

```
   AVAILABLE ◄──────────► BLOCKED        (operator, synchronous)
       │  ▲
       │  │
       ▼  │
     SOLD ─┘                             (converged from booking facts, asynchronous)

   WITHDRAWN — reachable in no code path (see §3.2)
```

| Transition | Performed by | Guard | Refused with |
|---|---|---|---|
| `AVAILABLE → BLOCKED` | block-seat | state is available | seat-unavailable (409) |
| `BLOCKED → AVAILABLE` | release-seat | state is blocked | seat-not-blocked (409) |
| `AVAILABLE → SOLD` | sold-fact consumer | state is available **AND** stored version < fact version | not-convergible (unrouted) |
| `SOLD → AVAILABLE` | released-fact consumer | state is sold **AND** stored version < fact version | not-convergible (unrouted) |

**Two independent predicates guard each converged transition** — an authority predicate (the prior
state) and an ordering predicate (the version). Both MUST hold. The authority predicate is what
stops a fact from silently freeing a *blocked* seat; the ordering predicate is what stops a stale or
redelivered fact from moving state backwards. They are not redundant and an implementation MUST NOT
collapse them into one.

### 3.1 Why blocking and selling are different mechanisms

Blocking is **operator intent**, applied synchronously and authoritative immediately. Selling is
**converged state**, applied asynchronously from facts and therefore lagging. They are deliberately
not the same mechanism, and the sellability question in §3.3 depends on the distinction.

### 3.2 `WITHDRAWN` is declared but unreachable

The state exists in the enum and is handled in every exhaustive match, but **no code path writes
it**. An implementation SHOULD keep it as a declared state with its handling defined (it means
"permanently removed from sale", distinct from the reversible "blocked"), and SHOULD NOT invent a
transition to it that the reference does not have. Treat it as reserved.

### 3.3 Seat sellability — a deliberately surprising answer

A dedicated read answers "may this seat be sold?" and returns:

| State | Sellable | |
|---|---|---|
| `AVAILABLE` | **yes** | |
| `SOLD` | **yes** | ← deliberate; see below |
| `BLOCKED` | no | operator withheld it |
| `WITHDRAWN` | no | permanently removed |

**`SOLD` answering "yes" is correct, not a bug**, and an implementation MUST preserve it. The
reasoning:

Seat state here converges asynchronously and therefore *lags a cancellation*. If this gate refused
on `SOLD`, there would be a window after every cancellation during which the seat is genuinely free
but this read still calls it unsellable — a real false refusal. And refusing would buy nothing,
because a seat with a live confirmed reservation is **already** refused by the claim statement in
booking, which is the lag-free authority on sold-ness.

So this gate answers only what event management is authoritative for — **operator withdrawal** — and
leaves sold-ness to the subsystem that owns it. An unknown seat is a *distinct* outcome (404), never
coerced to "not sellable".

This is the general principle: **each gate answers only what its subsystem is authoritative for.**

---

## 4. The reservation — one row per seat, forever

The most important structural decision in the system.

**The seat identifier IS the reservation's identity.** There is exactly one reservation record per
seat for all time; it is reclaimed in place, never duplicated. That record carries a separate,
**rotating claim identifier** that changes on every successful claim.

This split does real work:

- **The seat identifier never changes**, so it can be a stable primary key and the serialization
  point for contention (`09-guarantees.md` §1).
- **The claim identifier rotates**, so it is proof of *which* claim won. Every downstream guarded
  transition (confirm, release) is keyed by claim identifier, so a claim that has been rotated away
  by a concurrent reclaim fails closed — it cannot act on a seat it no longer owns.

**A booking records the claim identifier as a historical fact, not a live reference.** There is
deliberately **no foreign key** from bookings to reservations.

> **This is the fix for a real bug, and re-implementers will hit it too.** The original design used a
> surrogate reservation identity that *rotated in place*, with bookings holding a foreign key to it.
> Once a seat had ever been booked, reclaiming it violated that constraint — so a cancelled seat
> could never be resold, and a hold could never convert into a purchase. Both failed permanently.
> If your model has a foreign key from a sale record to a reclaimable reservation row, you have
> reproduced the bug.

### 4.1 Reservation states

`held` → `confirmed` → `cancelled` / `expired`. A held reservation carries an expiry; a confirmed one
does not.

### 4.2 The per-seat version

The reservation row carries a **monotonic counter bumped on every lifecycle transition** — claim,
confirm, release, cancel, expire, orphan-reap. Because there is exactly one row per seat, this
counter **totally orders all events for that seat**.

It says nothing across seats, and MUST NOT be used as a global sequence. Every published seat fact
carries the version of the transition that produced it, and every consumer guards on it.

---

## 5. Prices are appended, never updated

A price is **never modified in place**. Setting or adjusting a price appends a new entry at a
strictly higher version within its `(event, tier)` scope; the full history is retained permanently.

Two derived "latest value" projections exist over that log — one owned by pricing (authoritative,
written synchronously in the same operation as the append) and one owned by the quote subsystem
(written asynchronously from facts). They are mutable and latest-only; the log is the truth.

Both projections apply a **monotonic upsert**: an incoming version that does not strictly exceed the
stored version is silently ignored rather than applied or rejected. This makes replay and
out-of-order delivery converge.

An implementation MUST keep price history append-only. A correction is a new version, not an edit.

---

## 6. Reserved and dead structure

Present in the reference and carrying **no live semantics**. An implementation MAY omit all of it.
Listed so that a re-implementer does not infer requirements from it:

| Thing | Status |
|---|---|
| `WITHDRAWN` seat state | declared, handled, never written (§3.2) |
| Audit tables for events and bookings | created, never read or written by any process |
| A hold-expiry column on the availability projection | created, never referenced by any query |
| Seat-level price scoping | schema permits it; only tier-wide scoping is ever exercised |
| Currency-mismatch failure | defined; unreachable in pricing and quoting |
