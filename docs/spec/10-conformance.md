# Conformance

The behaviors an implementation must demonstrate. Each is stated as an observable scenario, not as a
test of internal structure — how you verify them is free.

Grouped by what they protect. **Group A is non-negotiable**: an implementation failing any of it is
not a ticketing system, it is a system that oversells.

---

## A. Contention and correctness

**A1 — Concurrent claims on one seat.** With N clients claiming the same available seat
simultaneously, **exactly one succeeds**; every other receives *seat unavailable*. No client blocks,
retries internally, or times out. Repeat under sustained concurrency; the property must not degrade.

**A2 — A claim never waits.** Under A1's load, a losing claim returns in time comparable to a
successful one. A design that serializes by waiting fails this even if A1 passes.

**A3 — Hold refresh does not extend indefinitely.** A client refreshing its own hold on a timer is
refused once 60 minutes have elapsed since the hold began, and the seat becomes claimable by others.

**A4 — An expired hold is immediately claimable.** With the sweep **disabled**, another customer can
claim a seat whose hold has lapsed. This proves expiry correctness does not depend on the sweep.

**A5 — A cancelled seat can be resold.** Buy a seat, cancel it, buy it again — with a *different*
customer, and again with the same one. Both succeed. *(This is the scenario the original data model
failed permanently; see `06-data-model.md` §8.)*

**A6 — A hold converts to a purchase.** Acquire a hold, then buy the same seat as the same customer.
Succeeds. *(Same origin as A5.)*

**A7 — A rotated-away claim cannot act.** Acquire a hold, let it lapse, let another customer claim
the seat, then attempt to complete the first claim. It fails; it does not affect the second
customer's reservation.

---

## B. Compensation and recovery

**B1 — Declined payment leaves no trace.** Force a decline mid-purchase. The caller gets *payment
declined*, the seat is claimable again, and no booking exists.

**B2 — A failure after authorization voids and releases.** Force a store failure after the gateway
approves. The authorization is voided, the seat released, and the caller sees the **original**
failure — not a compensation failure.

**B3 — An unusable receipt is treated as failure.** Force an approval carrying an unparseable
receipt. The purchase fails and the authorization is voided.

**B4 — Compensation failure does not mask the original.** Force *both* a post-authorization failure
and a gateway that is down for the void. The caller still receives the original failure.

**B5 — No orphaned confirmed booking.** Force a failure at each step after the seat claim. In every
case, no confirmed booking is observable — the invariant the write ordering exists to protect.

**B6 — Re-driven cancellation refunds once.** Force a failure after the refund is recorded but
before the booking closes, then re-drive. The gateway is called **exactly once**; the cancellation
completes.

**B7 — Cancellation converges.** Force a failure at each step of cancellation and re-drive. Each
converges to a cancelled booking, a freed seat, and one refund.

**B8 — Second cancellation is refused.** After a successful cancellation, cancelling again returns
*already cancelled*.

**B9 — Only the releasing attempt publishes.** In B7, exactly one released-fact is published per
cancellation, carrying the version of the transition that actually freed the seat.

**B10 — Orphans are reclaimed.** Create a confirmed reservation with no booking, age it past one
hour, run the sweep. The seat returns to sale.

**B11 — The reaper does not race a live purchase.** With purchases in flight continuously, run the
reaper repeatedly. No confirmed sale is ever cancelled.

---

## C. Ordering and convergence

**C1 — Redelivery is a no-op.** Deliver the same seat fact repeatedly. State converges once;
subsequent deliveries succeed without changing anything.

**C2 — A stale fact never regresses state.** Deliver a sold-fact, then a *lower-versioned*
released-fact. The seat remains sold. Then the reverse ordering: state remains correct.

**C3 — Both layers guard independently.** Verify C1 and C2 against **both** the authoritative seat
state and the availability projection. Neither protects the other.

**C4 — Authority and ordering are both enforced.** A sold-fact with a **higher** version must not
convert a **blocked** seat. Both predicates must hold.

**C5 — Redelivery and divergence are distinguished.** A fact whose version does not exceed stored
state is absorbed as success; a fact that is newer but hits a wrong prior state produces
*not convergible*. Conflating them fails.

**C6 — A poison fact does not wedge the consumer.** Deliver a fact with an unparseable identifier,
then a valid one. The valid one is processed.

**C7 — Price versions never collide.** Under concurrent price writes to one scope, every appended
version is distinct, history is contiguous, and every loser observes a failure. No two entries share
a version.

**C8 — Price projections are monotonic.** Deliver price facts out of order. Each projection settles
on the highest version.

---

## D. The API contract

**D1 — Every route enforces its access level** per `07-http-api.md` §1. Verify each rejects
insufficient access.

**D2 — Ownership is enforced beyond authentication.** An authenticated customer cancelling another
customer's booking receives *not owner*, and the booking is unchanged.

**D3 — Every failure maps to its specified status.** Exercise every entry in
`08-failure-catalog.md` and confirm the status. **No scenario returns 500.**

**D4 — Malformed and unacceptable are distinguished.** A blank section yields 400; a non-positive
seat number yields 422 — in the same request shape.

**D5 — The first invalid field is reported.** With several invalid fields, the response names the
first in declaration order.

**D6 — A missing on-sale time is an empty string**, not null, not absent.

**D7 — Money never round-trips through a float.** Amounts cross the wire as integer minor units.
Verify a value that a float would corrupt.

**D8 — Percentage scaling rounds half-up.** Verify with a value whose scaling lands exactly on a
half unit.

---

## E. The guarantees, honestly

These verify what the system **does not** promise. An implementation claiming more than the reference
should demonstrate it deliberately rather than by accident.

**E1 — Derived reads may be stale.** Sell a seat and read availability immediately. A stale answer is
**conforming**. An implementation returning a fresh answer here is providing a stronger guarantee and
should say so.

**E2 — Purchase never reads a stale price.** Change a price, then immediately buy. The charge is the
**new** price, regardless of what the customer-facing quote returns.

**E3 — Two instances may disagree.** With multiple read instances and caching, two simultaneous reads
of the same seat may differ. Verify this is bounded by cache lifetime and cannot affect a purchase.

**E4 — A lost fact does not self-heal.** Drop a fact, then verify the projection is wrong and stays
wrong. *This documents the reference's weakest property.* An implementation that adds reconciliation
should verify **recovery** instead, and record the deviation as an improvement.

**E5 — Contention refusals are not faults.** Under A1's load, confirm that error-rate monitoring,
retry budgets and circuit breakers do not treat *seat unavailable* as a fault. **A breaker that opens
under A1 fails this test**, and would take down purchasing at peak demand.

---

## F. Recommended deviations

Not required. Each closes a gap the reference leaves open; implementing any is an improvement, and
should be recorded as a deliberate deviation.

| Gap | Improvement |
|---|---|
| A lost fact is permanent and undetectable (E4) | Reconcile authoritative reservation state against converged seat and projection state, or use delivery with acknowledgement and replay |
| A version gap is invisible | Detect skipped versions per scope and alert — versions are monotonic, so gaps are detectable |
| A lost price-version race reads as a store outage | Surface a distinct retryable-conflict failure |
| A gateway timeout can strand an authorization | Use gateway idempotency keys issued and persisted **before** the call |
| Re-driven refunds depend on gateway deduplication | Same — persist an idempotency key before calling |
| A seat can be added to an event cancelled concurrently | Make the insert conditional on the event's status |
| Payment retry and circuit-breaking cannot attach | Give the gateway call its own boundary, with a trip predicate that excludes designed refusals (E5) |

---

## Claiming conformance

State which groups pass, list every deviation with its direction (weaker or stronger than this
specification), and name anything in §F you implemented. **An implementation that passes A and B but
not E4 is conforming** — E4 documents a weakness, and failing to reproduce a weakness is not a defect.
