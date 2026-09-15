# Processes — Booking

Five processes. This is the subsystem where the system's hardest guarantees live: contention,
compensation, and re-drivable recovery.

Shared constants, binding: **hold lifetime 15 minutes**, **hold lifetime cap 60 minutes**,
**stale-hold warning threshold 5 minutes before expiry**, **maximum simultaneous confirmed bookings
per customer 5**, **orphan reap age 1 hour**.

> Two of those five are **hidden requirements** in the reference — the booking cap and the stale
> threshold appear only as an unexplained constant and a bare inline literal, discoverable only from
> tests. They are stated here because a re-implementer would otherwise miss them entirely.

---

## B1. Acquire hold

Claim a seat with a decaying, time-bounded exclusive hold.

- **Trigger** — `POST /api/v1/booking/holds/`, **authenticated**.
- **Input** — customer, event, seat identifiers.
- **Output** — the claim identifier, and a state which is always literally `FRESH`.
- **Dependencies** — the booking store; the seat-sellability gate.

**Steps.** Validate → ask the sellability gate whether this seat may be sold at all (any failure, or
"not sellable", refuses) → attempt the guarded claim → return the claim identifier.

**Failures.**

| Failure | Status | Meaning |
|---|---|---|
| seat unavailable | 409 | another customer holds or owns it |
| seat not sellable | 409 | operator withheld it, or the gate could not answer |
| booking store unavailable | 503 | |
| invalid request | 400 | a field could not be parsed |

**Two distinct 409s, deliberately.** "Unavailable" is resolved by waiting for a hold to lapse;
"not sellable" is not. An implementation MUST keep them distinguishable even though they share a
status.

**Recovery — design-out.** See `09-guarantees.md` §1.

**Idempotency.** Re-acquiring a hold you already own refreshes its expiry but does **not** reset its
lifetime origin, so the 60-minute cap still applies. This is what stops a client on a refresh timer
from squatting a seat forever.

---

## B2. Check hold

Report a seat's hold decay state.

- **Trigger** — `GET /api/v1/booking/holds/{seat}`, **authenticated** — because the answer discloses
  that *someone* holds a seat.
- **Output** — one of `FRESH`, `STALE`, `EXPIRED`, `SOLD`, `NONE`.

**Classification, in this order — persisted state decides first:**

| Persisted state | Result |
|---|---|
| no record | `NONE` |
| held, past expiry | `EXPIRED` |
| held, within 5 minutes of expiry | `STALE` |
| held, otherwise | `FRESH` |
| confirmed | `SOLD` |
| expired | `EXPIRED` |
| anything else | `NONE` |

**The ordering is binding.** Decay flags are meaningless off a live hold — evaluating them first
would report a confirmed sale as expired.

**Failures.** Booking store unavailable (503); invalid request (400).

---

## B3. Buy ticket

Purchase a seat. The system's compensating saga.

- **Trigger** — `POST /api/v1/booking/buy/`, **authenticated**.
- **Input** — customer, event, seat, tier.
- **Output** — booking, ticket, seat, receipt identifiers, plus amount and currency.
- **Dependencies** — booking store; payment gateway; notification sender; the price quote, sale
  status and seat sellability reads; a publisher for the sold fact.

### Steps

1. **Validate.**
2. **Gate — three checks concurrently**, all of which must pass:
   - the event is on sale (any failure, or not-on-sale → *event not selling*);
   - the seat is sellable (any failure, or not-sellable → *seat not sellable*);
   - the customer has fewer than **5** confirmed bookings (→ *customer ineligible*).
3. **Price** — read the price **authoritatively and synchronously**. MUST NOT come from the cached
   customer-facing quote projection (`09-guarantees.md` §2.1).
4. **Reserve** — the guarded claim. Empty result → *seat unavailable*.
5. **Authorize** at the payment gateway. Declined → *payment refused*.
6. **Confirm** the reservation, keyed by the claim identifier won in step 4.
7. **Write ticket, then payment, then the booking record LAST.**
8. **Notify and publish** — both best-effort.

### The write ordering is binding

The booking record is the only artifact that later reads consult — the eligibility count, the
cancellation path. Writing it last means **every failure mode occurs before the sale is observable**,
so compensation reverses cleanly with nothing dangling. Ticket and payment records carry no reference
to the booking, so a stranded one is unreachable through any booking-keyed read.

### Compensation, per stage

| Failure after… | Compensation |
|---|---|
| the seat is claimed | release the reservation, then re-raise the original failure |
| the gateway approved | **void the authorization first** (best-effort), then release, then re-raise |

Compensation failures are swallowed — a gateway that is down during compensation leaves the
authorization for the provider's own expiry to reap. The original failure is always what the caller
sees; compensation never replaces it.

**An unparseable receipt from an approved authorization is treated as a failure**, and the raw
receipt string is voided before failing. Otherwise money is captured with no usable handle.

> **Known uncovered window, inherited from the reference.** If the gateway *times out* on an
> authorization that actually succeeded, the receipt identifier is never learned. Voiding is keyed by
> receipt, so nothing can be voided; the reservation is released and the stray authorization is left
> to the provider's expiry. An implementation with an idempotency-key-based gateway API can close
> this; the reference cannot.

### Failures

| Failure | Status |
|---|---|
| seat unavailable | 409 |
| event not selling | 409 |
| seat not sellable | 409 |
| payment declined | 402 |
| customer ineligible (booking limit) | 422 |
| booking store unavailable | 503 |
| payment provider unavailable | 503 |
| no price available | 503 |
| invalid request | 400 |
| unacceptable value | 422 |

### Publishing

Publishes a **seat-sold** fact carrying seat, event, booking and the reservation's version at the
confirming transition. Best-effort, single attempt, swallowed on failure — the booking is already
irreversible, so the response MUST stay truthful about the purchase even if the fact is lost.

---

## B4. Cancel ticket

Cancel a confirmed booking and refund it. **Not a compensating saga** — a refund cannot be
un-issued, so this is ordered and guarded for safe **re-drive** instead.

- **Trigger** — `POST /api/v1/booking/cancel/`, **authenticated**.
- **Input** — booking, customer.
- **Output** — booking identifier and refund receipt.
- **Dependencies** — booking store; payment gateway; a publisher for the released fact. **No other
  subsystem is consulted.**

### Steps

1. **Load** the booking. Missing → *not found* (404).
2. **Authorize, then check state** — in that order. Not the caller's booking → *not owner* (403).
   Already cancelled → *already cancelled* (409).
3. **Refund, idempotently.** Look for a recorded receipt first; if one exists, **replay it from
   storage and never call the gateway again**. Otherwise call the gateway, then record the receipt.
4. **Release and close** — invalidate the ticket (idempotent), release the seat (guarded; an empty
   result means an earlier attempt already freed it), then **close the booking LAST**.
5. **Publish** — only if *this* attempt actually performed the release.

### Why this order

- **Refund first.** Nothing the customer holds is taken away until their money is back. The state
  "seat released, payment kept" is unreachable by construction.
- **Booking closed last.** It is the record the cancellable-check reads, so it is the commit marker.
  Every earlier step is idempotent or guarded, so any failure leaves the whole operation re-drivable
  from the top; *already cancelled* is only ever returned once everything else has succeeded.

See `09-guarantees.md` §4 for the gateway-deduplication caveat, which an implementation **must**
resolve for its own gateway.

### Publishing only on the attempt that released

If the seat was already freed by a prior attempt, **publish nothing** — that attempt owns the
corresponding fact. Re-publishing would either invent a transition that did not happen or carry a
stale version. An implementation MUST reproduce this.

### Failures

| Failure | Status |
|---|---|
| booking not found | 404 |
| not owner | 403 |
| already cancelled | 409 |
| booking store unavailable | 503 |
| refund could not be completed | 503 |
| invalid request | 400 |

### Tail risk

If the final booking write fails, the seat is free and the money returned, but the booking still
reads confirmed. A re-drive closes it; failing that, the reaper (B5) reclaims the reservation by age.

---

## B5. Sweep holds

Reclaim reservations that time has invalidated. **This is the only process that frees either of the
two row classes below** — without it, those seats are lost permanently.

- **Trigger — two paths, same work:** a schedule at **60-second** intervals, single-execution; and
  `POST /api/v1/booking/holds/sweep/` at **operator** role, as a manual escape hatch.
- **Output** — the count of seats freed.
- **Failures** — booking store unavailable (503) only; there is nothing to validate.

**Steps.** Two reapers run concurrently over disjoint rows:

1. **Lapsed holds** — held reservations past expiry.
2. **Orphaned confirmations** — confirmed reservations **older than one hour** with no matching
   confirmed booking. These arise from a crash between confirming and writing the booking, or a
   cancellation whose final write failed. The claim guard never admits a confirmed reservation, so
   nothing else can ever free them.

Then publish one **seat-released** fact per freed seat, and return the count.

**The orphan reap MUST be a single statement.** Selecting orphans and cancelling them separately
would let a purchase in flight write its booking record in between — and the reap would then cancel a
live sale.

**Note an inconsistency inherited from the reference:** unlike every other publish in this subsystem,
the sweep's publishes are **not** individually swallowed; a publish failure propagates as part of the
aggregate result. An implementation MAY make this consistent with the others; if it does, it should
do so deliberately.

**The sweep is not the correctness mechanism for hold expiry** — see `09-guarantees.md` §5.
