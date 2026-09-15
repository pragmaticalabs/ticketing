# Guarantees

What each operation actually promises, the mechanism that earns it, and what happens when that
mechanism fails.

**This document deliberately avoids system-level labels.** "Eventually consistent", "strongly
consistent", "highly available", "exactly-once" each compress a system to one bit and hide the truth,
which is always per-operation. Nothing here is described that way. If you are porting this system and
find yourself writing one of those phrases in your own documentation, come back to this file: the
operation you are describing almost certainly has a sharper, smaller, more useful guarantee.

Read §1 and §2 before implementing anything. They contain the two facts most likely to produce a
system that passes tests and fails in production.

---

## 1. Seat contention — the central guarantee

> **Guarantee.** At most one customer holds or owns a given seat at any instant. Concurrent attempts
> on the same seat are serialized; every loser receives an immediate, definite refusal.

**Mechanism.** The seat identifier is the **primary key** of the reservation record. A claim is a
**single conditional upsert** — one statement, no read-then-write — whose conflict action is guarded
by a predicate over the current row. The database's row lock on the primary key does the
serialization; the guard decides whether the claimant may take the row.

The guard admits a claim in exactly three cases:
1. the current reservation is in a released state (cancelled or expired) — a genuine reclaim;
2. the current reservation is a hold whose expiry has passed — a lapsed hold;
3. the claimant is the current holder and the hold has not exceeded its lifetime cap — a refresh.

Any other state — a live hold by someone else, a confirmed sale — matches no branch, the statement
updates nothing, and the caller observes "no row returned" and fails with a seat-unavailable refusal.

**Why it is built this way.** This is *design-out*, the strongest of the three recovery strategies:
the invalid state is made unreachable rather than detected and compensated. There is no distributed
lock, no optimistic retry loop, no compensation path, because there is no window in which two
customers can both believe they hold the seat.

**What an implementation MUST preserve.** Two properties, not one technique:
- **Single-statement admission.** The decision to grant a seat MUST be one atomic operation against
  the row that represents the seat. Reading the reservation and then writing it — in two statements,
  in two round trips, or in application code — reintroduces exactly the race this defeats, and no
  amount of transaction isolation short of serializable makes the two-statement form equivalent.
- **Definite refusal.** A loser MUST get a distinct "seat unavailable" outcome immediately. It MUST
  NOT block, retry internally, or time out.

A key-value store with a compare-and-set primitive, or any engine offering a conditional atomic
write keyed by seat, satisfies this. A store offering only unconditional writes does not.

### 1.1 The trap: refusal is a success, not a fault

**On a popular event, most claim attempts fail, and that is the system working correctly.** The
seat-unavailable refusal is the *designed outcome* of contention, not an error.

This has a consequence implementers get wrong. Any infrastructure that counts failures — a circuit
breaker, a retry policy, an error-rate alert, an SLO burn-rate monitor — MUST NOT count this
refusal. Wiring a naive circuit breaker around the claim path means that at peak demand, when the
design is working exactly as intended, the breaker sees a flood of "failures" and opens, taking down
the purchase path precisely when it matters most.

The reference implementation hit this concretely: its circuit-breaker component classified every
typed failure as a trip-worthy fault with no way to supply a predicate, so it could not be adopted
on this path at all. Treat "which failures count as faults" as a required input to any resilience
component you attach.

---

## 2. Derived reads can be permanently stale

> **Guarantee.** A derived read (seat availability, sold count, customer quote) returns a value that
> was correct at some point in the past. **No bound on how far in the past is guaranteed.**

This is the second fact most likely to be lost in a port, because the obvious assumption — "it
catches up" — is false here.

**Mechanism.** Authoritative subsystems publish facts; derived subsystems consume them and update
projections. Delivery in the reference implementation is **at-most-once with a single attempt**: the
publish is best-effort and a failure is absorbed rather than retried. The consuming side has no
backfill, no reconciliation sweep, and no way to detect that it missed something.

**Therefore:** if a fact is lost, the projection for that seat is wrong **until the next fact about
that same seat arrives** — which may be never. A seat sold and never resold produces one
`SeatSold` fact in its lifetime; lose it and availability reports that seat as free permanently.

The reference implementation states this in its own code: *"a lost SeatReleased leaves availability
stale for that seat until the next fact about it."* That is the honest guarantee.

**Ordering, separately, IS handled.** Every seat fact carries a monotonic per-seat version, and
consumers guard on it: a projection accepts an update only when the incoming version exceeds the
stored one, and the authoritative convergence path additionally asserts the expected prior state.
So facts arriving **out of order** cannot corrupt a projection — a late-arriving older fact is
discarded. Loss and reordering are different problems; this system solves reordering and does not
solve loss.

**Per-instance caching widens the window further.** Read-path caching in the reference deployment is
**local to each instance and unreplicated**. Two instances can serve different answers for the same
seat until their entries expire. This is acceptable *only because* the underlying read is already a
bounded-staleness projection — the cache widens an existing window rather than introducing a new
kind of uncertainty. An implementation that makes these reads authoritative and then caches them
this way would be introducing a real defect.

### 2.1 What this means for callers

| Question | Ask | Never ask |
|---|---|---|
| "What seats might be free?" | the availability read model | — |
| "May I have this seat?" | the claim operation (§1) | the availability read model |
| "What is this seat's price to show?" | the quote read model | — |
| "What price am I charging?" | the authoritative pricing read | the quote read model |

**A derived read MUST NOT gate a decision that requires correctness.** Availability is a browsing
aid; the claim operation is the authority and will refuse. Purchase reads price
authoritatively — synchronously, from the owning subsystem — precisely because charging from a stale
projection would be a correctness bug and not merely a stale display.

---

## 3. Purchase — compensation, and the ordering that makes it safe

> **Guarantee.** A purchase either completes fully, or leaves no confirmed booking behind. It is
> never possible to observe a confirmed booking whose seat was not claimed or whose payment was not
> taken.

**Mechanism — write ordering, deliberately chosen.** Purchase spans a payment gateway and several
records, so it cannot be one transaction. It is a **compensating saga**, and its safety comes from
the *order* of the writes:

1. claim the seat (§1) — the serialization point;
2. take payment at the external gateway;
3. write the ticket and payment records — neither is referenced by any reader that decides;
4. **write the booking record LAST.**

The booking record is the one that later reads — "does this customer have an active booking?",
cancellation, counting — actually consult. Because it is written last, **any partial failure occurs
before the only observable artifact exists.** Compensation (void the payment, release the seat) then
reverses cleanly with nothing dangling.

**What an implementation MUST preserve:** the property that the record which makes the sale
*observable* is written after every step that can fail. Which record that is depends on your data
model; that it is last does not.

**What is NOT guaranteed.** There is no atomicity between the external payment and the local writes.
A crash after payment and before the booking write leaves payment taken and no booking. This is a
real window, and it is closed by §3.1, not by the saga.

**A second window the saga cannot close.** If the gateway **times out** on an authorization that
actually succeeded, the receipt identifier is never learned. Voiding is keyed by receipt, so there is
nothing to void: the seat is released and the stray authorization is left to the provider's own
expiry. The reference cannot close this. An implementation whose gateway accepts a client-supplied
idempotency key — issued and persisted *before* the call — can, and should.

**One eligibility rule rides on this path:** a customer may hold at most **5** simultaneous confirmed
bookings. This is a policy refusal (422), checked before any state changes. It is a hidden
requirement in the reference — an unexplained constant, discoverable only from a test.

### 3.1 The reaper — a bounded, not immediate, repair

A seat claimed and confirmed but never converted into a booking is invisible to every live process:
no cancellation will find it, and the claim guard does not admit confirmed reservations. Without
repair the seat is lost forever.

A background process reclaims such reservations once they are **older than one hour** and have no
corresponding booking, in a **single statement** — selecting orphans and cancelling them separately
would let a purchase in flight write its booking row in between, and the repair would then cancel a
live sale.

> **Guarantee.** A seat orphaned by a mid-purchase crash returns to sale **within roughly one hour
> plus one sweep interval** — not immediately.

That hour is a deliberate floor: it must exceed the longest plausible in-flight purchase, or the
repair races live traffic.

---

## 4. Cancellation — refund first, re-drivable

> **Guarantee.** Cancellation may be re-driven after any failure and converges. Nothing the customer
> holds is taken away before their money is returned. A second successful cancellation of the same
> booking is refused as already-cancelled.

**Mechanism — ordering again, in the opposite direction from purchase.** The refund happens
**first**; state changes follow, and the booking record — the one that decides whether cancellation
is still permitted — is closed **last**. Every intermediate step is idempotent or guarded, so a
failure at any point leaves the whole operation safely re-drivable from the top.

**Refund idempotency.** Before calling the gateway, the process looks for a **recorded refund
receipt** for the booking. If one exists it is replayed from storage and the gateway is never called
again.

> **Caveat, stated plainly.** That check protects the window *after* the receipt is stored. It does
> not protect the window *between* a successful gateway refund and the write that records its
> receipt. A crash in that window causes a re-drive to call the gateway again. Double-refund safety
> there depends on **the payment gateway being idempotent per booking** — the request is keyed by
> booking identifier, but nothing in this system enforces the gateway's behavior.
>
> **An implementation MUST verify this property of its own gateway.** If the gateway does not
> deduplicate by that key, this window must be closed another way — an idempotency key issued and
> persisted before the call, or a reconciliation against gateway records.

**Publishing is best-effort (forward degradation).** Once the refund and every write have succeeded,
the cancellation *has happened*, and a re-drive would now be refused as already-cancelled. So a
failure to publish the seat-released fact MUST NOT fail the operation — that would report a
completed cancellation as failed. The cost is §2's: availability may show the seat as taken until
another fact about it arrives.

**One subtlety worth copying.** The released-seat fact is published **only by the attempt that
actually performed the release**. A re-drive that finds the seat already freed publishes nothing,
rather than inventing a transition that did not happen or republishing a stale version.

---

## 5. Hold lifetime

> **Guarantee.** A seat held continuously by one customer is released no later than a fixed cap
> (reference: 60 minutes) after that hold began, regardless of client behavior.

**Mechanism.** A hold expires after a short window (reference: 15 minutes). The holder may refresh
it — but the guard admits a refresh only while the hold's **original start time** is within the cap.
The start time is preserved across refreshes and reset only on a genuine reclaim by a different
party. A client refreshing on a timer therefore cannot squat a seat indefinitely: refreshes stop
being admitted at the cap.

**The expiry sweep is garbage collection, not the correctness mechanism.** A background sweep marks
lapsed holds expired on an interval (reference: 60 seconds). It is tempting to think a seat is
unavailable until the sweep runs — it is not. The claim guard admits a lapsed hold directly
(§1, case 2), so an expired hold is claimable by the next customer **immediately**, whether or not
the sweep has run. The sweep exists to make state tidy and to emit release facts, and an
implementation MAY schedule it differently without affecting correctness.

---

## 6. Price history

> **Guarantee.** Prices are append-only and versioned per (event, tier). Concurrent price writes
> never interleave into a corrupt history; a losing writer fails visibly rather than silently
> overwriting.

**Mechanism.** A new price is appended in a **single statement** that allocates its version by
deriving it from the current maximum within the same statement, under a **uniqueness constraint on
(event, tier, version)**. Two concurrent appends therefore cannot both succeed with the same
version — the loser's write is rejected and the caller sees the failure.

A read-then-insert equivalent is **not** conforming: it reintroduces the allocation race, and the
uniqueness constraint would then surface it as a spurious error rather than preventing it.

**The diagnosis is weaker than the guarantee.** In the reference, the loser's rejection is remapped
by a blanket handler into the generic *pricing store unavailable* (503), so a caller cannot tell "the
store is down" from "you lost a race, retry and you will succeed" — and nothing retries
automatically. History is never corrupted, which is the guarantee; but the caller is told the wrong
thing about why. An implementation SHOULD surface a distinct retryable-conflict outcome.

Prices are never updated in place. A price change is a new version; history is retained.

---

## 7. Authorization

Every route declares its required access level explicitly. There is no implicit default: operator
and administrative surfaces (event lifecycle, seat capacity, price scheduling) require an
administrative role; customer operations require an authenticated customer; read-only query surfaces
are public. `07-http-api.md` carries the per-route table.

**Ownership is enforced separately from authentication.** Being an authenticated customer does not
grant access to another customer's booking: cancellation checks ownership and refuses with a
distinct "not owner" outcome, which is a different failure from "not found" and maps to a different
status. An implementation MUST keep those two distinguishable — collapsing them changes the API's
observable behavior. (Note that keeping them distinguishable does leak the existence of a booking
identifier to an authenticated non-owner; that is the reference behavior, and an implementation with
a stricter disclosure policy should treat it as a deliberate, documented deviation.)

---

## 8. What the reference implementation does NOT verify

Stated so that a re-implementer does not over-trust the reference as an oracle.

- **Schema/query agreement is only partly checked.** The reference generates data access from
  annotated SQL and validates it against the schema at build time — but the validator's output-column
  check only engages for statements shaped a particular way. Measured on this codebase, **12 of 14
  write statements that return columns were never output-validated at all**, including the seat-claim
  statement that §1 rests on. Table names and assigned columns are checked; returned columns and
  `WHERE`-clause columns in those statements are not. (Filed upstream; the fix will also convert
  today's silent skips into real validation.) **Do not assume the reference's queries are
  schema-verified.** Test them.
- **The system has never run end-to-end.** Every guarantee above is pinned by unit tests against
  in-memory doubles. The full assembly has not been exercised live, because the reference runtime's
  deployable archive lacks the external-integration providers.
- **Delivery semantics were assessed, not proven.** The at-most-once characterization in §2 comes
  from reading the publish path, not from a fault-injection campaign.

An implementation that runs the conformance behaviors in `10-conformance.md` against a live system
will, in these respects, be **better verified than the reference**.
