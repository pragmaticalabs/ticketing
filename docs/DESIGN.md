# Ticketing Platform — Design (PFD → JBCT → Aether)

Reference implementation of the event-ticketing example threaded through the PFD book
(`book-pfd/spiral-1..4`, `architecture-synthesis.md`). This is the **enterprise profile**'s
process design, realized as Aether slices on the unified runtime. Deployment-profile concerns the
book describes (multi-region, distributed store, polyglot, geo-scaled read tier) are noted where
they would attach but are not physically built — one local slice project embodies the **process
designs**, not the global topology.

Base package: `org.pragmatica.example.ticketing`.

---

## 1. Topology — five subsystems on one runtime (24 single-use-case slices)

Each row is a **subsystem**; the runtime composes the single-use-case slices inside it (in-process or
across nodes). Slice counts: eventmanagement 10, pricing 3, booking 5, availability 4, quote 2 = **24**.
**19 are HTTP-routed**; the 5 fact consumers (`convergence/`, `projection/`) carry no route.

| Subsystem | Kind | Owns (write) | Recovery | Substrate role |
|-------|------|--------------|----------|----------------|
| `eventmanagement` | core | events, seats (structure), event audit | BER (capacity) + design-out (seat convergence) | subscribes `SeatSold`/`SeatReleased`; direct: `saleStatus` + `seatSellability` reads |
| `pricing` | core | price_events (append log), current_price projection | design-out (append-only, idempotent) | publishes `PriceChanged`; direct: `quote` read (authoritative, for booking) |
| `booking` | core | reservations, bookings, payments, tickets, booking audit | **BER** saga + **FER** holds + design-out claim | publishes `SeatSold`/`SeatReleased`; direct→`pricing.quote`, `eventmanagement.saleStatus`, `eventmanagement.seatSellability`; `@Http` payment; `@Notify`; `Scheduled` hold sweep |
| `availability` | read (split, scaled) | seat_availability projection | design-out (idempotent convergence) | subscribes `SeatSold`/`SeatReleased` |
| `quote` | read (split, scaled) | price_view projection | design-out | subscribes `PriceChanged` |

**All six PFD patterns appear:** Leaf (VO factories, adapters), Sequencer (saga bodies),
Fork-Join (check-selling ∥ check-eligibility ∥ check-sellability), Condition (cancellable?, hold decay
state), Iteration (expired-hold/block sweep), Aspects (declared compensation; cache and logging
interceptors attached at the slice boundary — §11).

**All three recovery classes appear:** BER (booking money/reservation saga + cancel compensation),
design-out (seat claim via DB constraint; pricing append log; idempotent event convergence),
FER (holds `Fresh→Stale→Expired` time-as-decay; best-effort notification).

**Substrate = mixed (the book's enterprise answer):** *events across* (typed versioned facts over
pub-sub), *direct within and for synchronous reads* (booking→pricing quote, booking→event saleStatus).

---

## 2. Shared value objects — package `shared`

All id VOs follow the existing house idiom (`SeatId.java`): `record XId(Uuid value)` + sealed
`Error extends Cause { Blank, Malformed }` + factories `xId(String)→Result<XId>`, `xId(UUID)`,
`newXId()`. Core ships `Uuid` (`org.pragmatica.lang.vo.Uuid`); no `Money`/`Percent` — we add them.

- **Ids:** `CustomerId`, `EventId`, `SeatId`, `BookingId`, `TicketId`, `ReceiptId`, `HoldId`,
  `EventScheduleId`.
- **`Money`** — `record Money(long amountMinor, Currency currency)`; factory
  `money(String amount, String currency)→Result<Money>` (parse to minor units, validate currency).
  **Non-negative by construction**: a record's canonical ctor is public and cannot return a `Result`,
  so the invariant is held at every construction boundary instead (`money(...)` validates, `plus`/`sum`
  add non-negatives, `scaledByPercent` takes a positive `Percent`); no reachable path mints a negative
  `Money`. Ops `plus`, `scaledByPercent(Percent)` for demand adjustment. `Currency` enum
  (USD, EUR, GBP).
- **`Percent`** — `record Percent(long value)` (110 = +10%, 90 = -10%); factory
  `percent(long)→Result<Percent>`, **strictly positive by construction** (`NonPositive` failure). A
  positive percentage applied to a non-negative `Money` can never drive it to zero or below, so
  `Money.scaledByPercent(Percent)` keeps the result non-negative with no further check.
- **`PriceTier`** — enum `PREMIUM, STANDARD, ECONOMY, ACCESSIBLE, RESTRICTED_VIEW`.
- **`SeatState`** — enum `AVAILABLE, BLOCKED, SOLD, WITHDRAWN` (the seat-lifecycle state machine);
  factory `seatState(String)→Result<SeatState>` + `dbValue()` (lowercase form). The authoritative
  transitions live in eventmanagement's `seats` table (guarded SQL); this enum is the shared
  vocabulary the `seats`/`seat_availability` `state` values must match.
- **`SeatLocation`** — `record SeatLocation(String section, String row, int number)` + validating
  factory.

These are *genuinely shared* (their change-driver set is independent of any using process — PFD
Foundations "what stays shared"). Per-process Request/Response/Failure types stay in each slice.

---

## 3. Cross-slice facts — package `shared.event` (versioned contract types)

Plain records; slice-processor generates their codecs. One fact type per pub-sub topic (Aether
pub-sub is one-type-per-topic, no ordering/replay — every consumer is idempotent, and the seat facts
additionally carry a version so out-of-order delivery is *ignorable* rather than merely harmless).

| Fact record | Fields | Topic constant | Publisher | Subscribers |
|-------------|--------|----------------|-----------|-------------|
| `SeatSold` | seatId, eventId, bookingId, **version** | `SeatSold.SEAT_SOLD` → `"seat-sold"` | booking (`BuyTicket`) | eventmanagement, availability |
| `SeatReleased` | seatId, eventId, **version** | `SeatReleased.SEAT_RELEASED` → `"seat-released"` | booking (`CancelTicket`, `SweepHolds`) | eventmanagement, availability |
| `PriceChanged` | eventId, seatId, tier, amountMinor, currency, version | `PriceChanged.PRICE_CHANGED` → `"price-changed"` | pricing (`SetPrice`, `AdjustPrice`) | quote |

Topics are **typed constants** — `Topic.of("seat-sold", SeatSold.class)` declared on the fact record
itself (`org.pragmatica.aether.slice.topic.Topic`), so publisher and subscriber agree by type rather
than by a repeated string literal. The blueprint generator still validates one `resources.toml`
section per *resolved* topic name, so the kebab-case `[seat-sold]` sections must stay alongside the
constants.

**The seat-fact version** is the `reservations.version` of the transition that produced the fact (§5,
V008). It travels with the fact so every downstream writer can reject a stale one; see §5 for the two
different mechanisms that consume it.

Facts carry primitive/string-ish fields (UUID strings, longs) to keep wire codecs trivial; the
slices parse them back into VOs on receipt.

Qualifier convention (per Aether pub-sub): each fact has a pair of **dedicated wrapper annotations**
centralized in `shared.event`, not raw per-slice `@ResourceQualifier` — required, because
`@ResourceQualifier` is `@Target(ANNOTATION_TYPE)` only and cannot be applied to a parameter or
method directly. The publisher annotation (e.g. `@SeatSoldPublisher`) targets a PARAMETER and wraps
`@ResourceQualifier(type = Publisher.class, config = "SEAT_SOLD")`; the subscription annotation
(e.g. `@SeatSoldSubscription`) targets a METHOD and wraps the `Subscriber` form against the same
constant.

**Direct (synchronous) inter-slice calls** — inject the callee slice interface as an unannotated
factory parameter (processor generates the proxy):
- `BuyTicket` takes `QuotePrice` → authoritative price during Buy.
- `BuyTicket` takes `SaleStatus` → the book's "synchronous sale-status check, a read not a command".
- `BuyTicket` and `AcquireHold` take **`SeatSellability`** → the authoritative seat-state gate (§4.1).

---

## 4. Slice designs (six-property processes)

### 4.1 `eventmanagement` (10 slices)
Owns the venue/seat structure and event lifecycle. current-state + audit-as-data. Lifecycle
`CreateEvent`→`OpenEvent`→`CancelEvent`, capacity `AddSeat`/`BlockSeat`/`ReleaseSeat`, the
`SeatSellability` and `SaleStatus` reads, and the convergence consumers
`MarkSeatSold`/`MarkSeatReleased`.

**CreateEvent** — trigger: operator HTTP. in: `{venue, onSaleAt}`. out: `{event}`. failures:
`FieldRejected.BLANK_VENUE`, `MalformedOnSaleAt`, `ServiceUnavailable.EVENT_MANAGEMENT_STORE`
(no `InvalidRequest` — this slice takes no value-object input). steps: validate venue (non-blank) **and parse
`onSaleAt` → `IsoDateTime`** together via `Result.all` → insert event in `'draft'` → respond. (No fact
published; a garbage timestamp can no longer be persisted verbatim.)

**OpenEvent** — trigger: operator HTTP. in: `{event}`. out: `{event}`. failures: `InvalidRequest`,
`EntityMissing.EVENT`, `LifecycleConflict.EVENT_ALREADY_OPEN`, `LifecycleConflict.EVENT_CANCELLED`,
`TransitionRaced`, `ServiceUnavailable.EVENT_MANAGEMENT_STORE`. steps: validate → ensure event exists → guarded
`UPDATE … SET status='on_sale'` (no fact published).

**CancelEvent** — trigger: operator HTTP. in: `{event}`. out: `{event}` (withdrawn). failures:
`InvalidRequest`, `EntityMissing.EVENT`, `TransitionRaced`, `ServiceUnavailable.EVENT_MANAGEMENT_STORE`.
steps: validate → guarded `UPDATE … SET status='cancelled'
RETURNING` → respond.

**AddSeat** — trigger: operator HTTP. in: `{event, section, row, number, tier}`. out: `{seat}`.
failures: `InvalidRequest`, `UnacceptableValue`, `EntityMissing.EVENT`,
`LifecycleConflict.EVENT_CANCELLED`, `ServiceUnavailable.EVENT_MANAGEMENT_STORE`.
steps: validate (`EventId` + `SeatLocation` + `PriceTier`
via `Result.all`) → ensure event exists → insert seat in `'available'` → respond.

**BlockSeat** / **ReleaseSeat** (BER, defined inverses) — trigger: operator HTTP. in: `{seat}`. out:
`{seat}`. guarded transitions: block flips `'available'→'blocked'` (failure
`StateConflict.SEAT_UNAVAILABLE`), release
flips `'blocked'→'available'` (failure `StateConflict.SEAT_NOT_BLOCKED`); both also take
`InvalidRequest` and surface `ServiceUnavailable.EVENT_MANAGEMENT_STORE`.

**SeatSellability(seat)** — the seam that makes `BlockSeat` mean something. The `seats` table is owned
by event-management, so booking must not read it; instead `AcquireHold` and `BuyTicket` inject this
slice. in: `{seat}`. out: `{seat, state, sellable}`. failures: `InvalidRequest`, `EntityMissing.SEAT`,
`ServiceUnavailable.EVENT_MANAGEMENT_STORE`. `sellable` is an exhaustive switch over `SeatState`: `AVAILABLE`, `SOLD` → true;
`BLOCKED`, `WITHDRAWN` → false. **`SOLD` is deliberately sellable here** — this gate answers only
"is this seat withheld from sale?"; whether it is *already taken* is decided by the reservation claim
(§4.3), which is the single serialization point and must stay the only one. Routed publicly
(`GET /api/v1/seats/sellability/{seat}`): it renders a seat map and carries no customer, booking or
price data.

*Before this slice existed, `BlockSeat` was inert* — it flipped the `seats` row, but nothing on the
booking path read it, so a blocked seat could still be held and sold.

**SaleStatus(event)** — direct read for booking (also HTTP-routed). in: `{event}`. out:
`{event, onSale, onSaleAt}` (`onSale` = status is `'on_sale'`). failures: `InvalidRequest`,
`EntityMissing.EVENT`, `ServiceUnavailable.EVENT_MANAGEMENT_STORE`.

**MarkSeatSold / MarkSeatReleased** — pub-sub subscribers (`@SeatSoldSubscription` /
`@SeatReleasedSubscription` on `execute(SeatSold)` / `execute(SeatReleased)`, no HTTP route). Each
applies a **doubly-guarded** `UPDATE seats SET state = …, version = :version WHERE id = :id AND
state = <expected> AND seats.version < :version RETURNING id`. The two predicates refuse for
different reasons and the slice tells them apart: a stored version at or beyond the fact's means the
fact was already applied or overtaken (a no-op — this is what makes redelivery safe); a stored
version *below* the fact's with the state predicate refusing means booking and event-management
genuinely disagree, which is reconciled rather than swallowed.

Unlike the availability projections, these two do **not** blanket-`recover` — a failure propagates as
a typed cause. Both failures stay **data-carrying records** rather than fixed-message enum constants,
because each names the offending seat: `SeatNotFound(seat)` and `SeatNotConvergible(seat, state)`.
Neither slice is HTTP-routed, so neither has a `routes.toml` or an HTTP status.
**Honest limit, recorded in the slice's own doc comment:** a fact that is never
delivered at all is invisible to a version guard, so booking can hold a seat sold while `seats` still
reads available. Closing that needs durable subscriptions or a reconciliation sweep; neither exists
here (§8.2 of `AETHER-WISHLIST.md`).

Tables: `events(id, venue, on_sale_at, status)` — status ∈ draft|on_sale|cancelled; `seats(id,
event_id, section, seat_row, number, tier, state)` — state ∈ available|blocked|sold|withdrawn;
`event_audit(id, event_id, kind, at, detail)`.

### 4.2 `pricing` (3 slices)
Append-only price history (design-out, "event-sourced-flavored") + a current_price projection.

**SetPrice** — trigger: operator HTTP. in: `{event, tier, amount, currency}`. out: `{version}`.
failures: `InvalidRequest`, `UnacceptableValue` (restating VO validation of
`EventId`/`PriceTier`/`Money`), `ServiceUnavailable.PRICING_STORE`. steps: validate →
**append** row to `price_events` (version allocated atomically; see §5) → upsert `current_price`
projection → **publish `PriceChanged`** → respond. recovery: design-out (correction = a new append at a
higher version, never an overwrite).

**AdjustPrice** — trigger: operator/internal HTTP. in: `{event, tier, percent}` (a `long`, 110 = +10%).
out: `{version}`. failures: `EntityMissing.PRICE`, `ServiceUnavailable.PRICING_STORE`,
`InvalidRequest`, `UnacceptableValue` (the latter covers a non-positive
`Percent`). steps: read current price → scale via **`Money.scaledByPercent(Percent)`** → append new
version → upsert → publish → respond. recovery: design-out.

**QuotePrice** — authoritative read used by booking (direct; also HTTP-routed). in: `{event, tier}`.
out: `{event, tier, amountMinor, currency, version}`. failures: `EntityMissing.PRICE`,
`ServiceUnavailable.PRICING_STORE`, `InvalidRequest`, `UnacceptableValue`.

Tables: `price_events(id, event_id, seat_id NULL, tier, amount_minor, currency, version, created_at)`,
`current_price(scope_key PK, event_id, seat_id NULL, tier, amount_minor, currency, version, updated_at)`.

### 4.3 `booking` (5 slices — the centerpiece)
Owns reservations/holds, bookings, payments, tickets. current-state + audit-as-data.

**BuyTicket** — the BER saga. trigger: customer HTTP POST. in: `{customer, event, seat, tier}`. out:
`{booking, ticket, seat, receipt, amountMinor, currency}`. failures (closed set):
`StateConflict.SEAT_UNAVAILABLE`, `StateConflict.SEAT_NOT_SELLABLE`, `StateConflict.EVENT_NOT_SELLING`,
`Unprocessable.CUSTOMER_INELIGIBLE`, `PaymentRefused.DECLINED`, `ServiceUnavailable.PRICE`,
`ServiceUnavailable.PAYMENT_PROVIDER`, `ServiceUnavailable.BOOKING_STORE`, `InvalidRequest`,
`UnacceptableValue`.
steps (Sequencer + Fork-Join):
1. validate → `ValidBuy` (VOs via `Result.all`, failures restated — §12)
2. **Fork-Join**: `saleStatus.execute(event)` ∥ `seatSellability.execute(seat)` ∥ count customer's
   active bookings — join gates on selling + sellable + eligibility
3. `quotePrice.execute(event, tier)` → price (direct)
4. **reserve** — design-out claim: a single guarded `INSERT … ON CONFLICT (seat_id) DO UPDATE … WHERE`
   against `seat_id` as the reservation **primary key**; zero rows = `StateConflict.SEAT_UNAVAILABLE`, while a
   stale/expired hold is reclaimed inline. Returns the rotated `claim_id` + the new `version` (§5)
5. **authorize payment** — `@Http` gateway `POST /authorize` → `AuthResult{approved, receiptId}`;
   declined → `PaymentRefused.DECLINED`, transport failure → `ServiceUnavailable.PAYMENT_PROVIDER`
6. **confirm** — guarded `UPDATE reservations SET state='confirmed'` **matched on `claim_id`**, then
   insert `tickets(issued)`, `payments(authorized)`, and the `bookings` row **LAST** (so any partial
   store failure precedes the confirmed booking)
7. **notify** — `@Notify` best-effort (**FER**: `.recover` so a notify failure does not fail the buy)
8. **publish `SeatSold`** carrying the reservation `version`, best-effort; respond.

**Compensation, stated precisely.** Every failure path after step 5 that holds a usable receipt
attempts `voidAuthorization` before releasing the reservation and re-raising the original cause —
an unparseable receipt voids-then-fails, and any confirm-step failure voids-then-releases. The
authorize-step path releases the reservation only, because it is reached only when no usable receipt
exists. Two honest limits: **the void is itself best-effort** (it ends in `.recover`, so a failing
void is swallowed and the authorization is left standing), and **a gateway timeout that in fact
authorized is undetectable** — there is no reconciliation against the gateway. `SeatSold` is likewise
published best-effort, so a publish failure leaves the projection and the authoritative `seats` row
behind with nothing to detect it. Releases are guarded by `claim_id`, not by state, so compensation
can release a reservation it already confirmed but can never release a claim that has since rotated
away from it.

**CancelTicket** — saga + compensation, **refund-first**. trigger: customer HTTP. in:
`{booking, customer}`. out: `{booking, receipt}`. failures: `EntityMissing.BOOKING`,
`AccessRefused.NOT_OWNER`, `StateConflict.ALREADY_CANCELLED`, `ServiceUnavailable.PAYMENT_GATEWAY`
(a refund the gateway would not complete), `InvalidRequest`, `ServiceUnavailable.BOOKING_STORE`.
steps: validate → load booking → ensureCancellable (Condition; pure — owner, then not-cancelled) →
**refund (`@Http`)** → release-and-close (invalidate ticket → cancel reservation by seat → cancel
booking **last**) → **publish `SeatReleased`** → respond.

The refund precedes every reservation/booking/ticket write, so the money is returned before any
state the customer can observe changes. **Re-drivable, not idempotent** — three distinct mechanisms,
none of them a unique constraint:
- the refund is deduplicated by a **read**: an existing `payments` row with `status = 'refunded'` and
  a non-null `receipt_id` short-circuits and replays the stored receipt; the gateway is never called
  twice on a re-drive;
- `markRefunded` is state-guarded (`WHERE booking_id = :id AND status = 'authorized'`);
- `cancelBooking` is state-guarded (`WHERE id = :id AND status = 'confirmed' RETURNING id`).

So re-driving a **failed** attempt is safe and converges. A second **successful** call is not a
no-op returning the same response — it fails `StateConflict.ALREADY_CANCELLED`. The fact is published only when
*this* attempt actually performed the release. recovery: BER.

**Hold** (FER time-as-decay) — `AcquireHold`, `CheckHold`. `AcquireHold` is the same design-out claim
as reserve, with a 15-minute TTL (state `'held'`), gated first through `SeatSellability` so a blocked
seat cannot be held (failure `StateConflict.SEAT_NOT_SELLABLE`); the loser of a contended seat fast-fails
`StateConflict.SEAT_UNAVAILABLE`. `CheckHold` reports the hold's
decay as a **String label** `FRESH` / `STALE` / `EXPIRED` / `NONE`, computed at read from `expires_at`
(a Condition over persisted state — the book's FER; **no** sealed `HoldState` type). A missing row
reads as `NONE`; apart from `InvalidRequest`, the only failure is `ServiceUnavailable.BOOKING_STORE`.

**SweepHolds** — **`Scheduled`** (60s interval), via a custom `@SweepSchedule` qualifier on a
zero-parameter `sweep()` that delegates to `execute(new Request())`. The operator HTTP route
(`POST /api/v1/booking/holds/sweep`, `role:operator`) is **kept alongside** the schedule so a sweep
can be forced. Iteration — expire held-but-stale rows (`RETURNING` the freed seats and their
versions), then publish `SeatReleased` per freed seat; out `{released}`. Its only failure is
`ServiceUnavailable.BOOKING_STORE`. recovery: FER (forward
progress; nothing to compensate).

Tables: `reservations(seat_id PK, claim_id, event_id, customer_id, state, expires_at, created_at,
held_since, version)` — **`seat_id` is the primary key**, one row per seat forever, and that is the
design-out serialization point (§5);
`bookings(id, reservation_claim_id, seat_id, event_id, customer_id, status, ticket_id)` — note
`reservation_claim_id` is a **plain column, not a foreign key**;
`payments(id, booking_id, status, receipt_id, amount_minor, currency)`; `tickets(id, booking_id,
seat_id, status)`; `booking_audit(id, booking_id, kind, at, detail)`.

### 4.4 `availability` (read, split, scaled separately)
Subscribes `SeatSold` / `SeatReleased` → maintains the `seat_availability` projection via
`ProjectSeatSold` / `ProjectSeatReleased`. The upsert is **monotonic**: `INSERT … ON CONFLICT
(seat_id) DO UPDATE … WHERE seat_availability.version < EXCLUDED.version`, so a late or redelivered
fact for a seat cannot move the projection backwards. Both projections `.recover(_ -> unit())` inside
`execute` — a store failure is swallowed and the projection simply stays stale until a later fact for
that seat arrives (bounded staleness with **no** lower bound on recovery time; nothing retries).
Exposes **`SoldCount(event)`** (out
`{event, sold}`) and **`SeatStatus(seat)`** (out `{seat, state}`; a seat with no projection row reads
as `available`) — bounded-staleness reads. Each declares only `InvalidRequest` and
`ServiceUnavailable.AVAILABILITY_STORE`: a projection that has not caught up is a stale answer, not a
failure, so there is no 404 here. Own blueprint with higher `instances` — demonstrates the
read-path split and independent scaling. Table: `seat_availability(seat_id PK,
event_id, state, hold_expires_at, updated_at, version)`. **Persistence is segregated per use case** (no slice
needs >1 method): `SeatStatusStore` (`findStatus`), `SoldCountStore` (`countSold`), and a shared
`SeatProjectionStore` (`upsertStatus`) for the two projections — interface segregation, not one
subsystem store. State values flow through the shared `SeatState` enum (`SeatState.SOLD.dbValue()`
etc.).

### 4.5 `quote` (read, split, scaled separately)
Subscribes `PriceChanged` → `price_view` projection (via `ProjectPrice`). Exposes the customer-facing
**`QuoteForCustomer`** read, in `{event, tier}` → out `{event, tier, amountMinor, currency, version}`
(distinct from pricing's authoritative `QuotePrice` used by booking). failures:
`EntityMissing.PRICE`, `ServiceUnavailable.QUOTE_STORE`, `InvalidRequest`, `UnacceptableValue`.
Own blueprint, higher instances.
Table: `price_view(scope_key PK, event_id, seat_id NULL, tier, amount_minor, currency, version, updated_at)`.
**Persistence is segregated per use case**: `QuoteViewStore` (`findByScope`) for the read,
`PriceProjectionStore` (`upsertPrice`, monotonic) for the projection.

---

## 5. Schema migrations (`src/main/resources/schema/`)
Sequential, idempotent, double-underscore. **Nine migrations, `V001`–`V009`.** The first six are one
per subsystem: `V001__initial.sql` (an empty placeholder — no shared DDL), `V002__eventmanagement.sql`,
`V003__pricing.sql`, `V004__booking.sql`, `V005__availability.sql`, `V006__quote.sql`. The last three
are the booking-identity and ordering work:

- **`V007__booking_seat_identity.sql` — the seat becomes the reservation identity.** Drops
  `reservations.id` and its primary key, makes **`seat_id` the primary key**, and adds a non-key
  `claim_id UUID NOT NULL` (backfilled from the old `id`) that `gen_random_uuid()` rotates on every
  successful claim. On the other side it **drops the `bookings → reservations` foreign key** and
  renames the column to `reservation_claim_id`. The point: a claim is a **point-in-time fact**, not an
  identity. This unblocked two states that were previously permanent failures — **reselling a
  cancelled seat** (the FK made the reservation row un-reusable, so the second sale raised a
  violation forever) and **converting a hold into a purchase** (which had always failed).
- **`V008__seat_fact_versions.sql` — per-seat fact versions.** Adds `version BIGINT NOT NULL
  DEFAULT 0` to `reservations`, `seat_availability`, and `seats`. `reservations.version` is the
  source: it increments on every claim/confirm/release, travels on the `SeatSold`/`SeatReleased`
  fact, and lets both downstream writers reject a stale one. **The two writers use different
  mechanisms and they are not interchangeable:** the availability projection is an upsert guarded by
  `… DO UPDATE … WHERE seat_availability.version < EXCLUDED.version`, whereas the authoritative
  `seats` write is a **guarded `UPDATE`** — `WHERE id = :id AND state = <expected> AND seats.version
  < :version RETURNING id` — which additionally asserts the expected prior state and returns an empty
  projection when it refuses, so event-management can tell "already applied" from "we genuinely
  disagree" (§4.1). `PricingStore.upsertCurrent` already used the `EXCLUDED`-guarded form for
  `current_price` before V008; the seat facts adopted the same idea.
- **`V009__hold_lifetime_cap.sql` — bounded hold lifetime.** Adds `held_since TIMESTAMPTZ NOT NULL
  DEFAULT now()` (backfilled from `created_at`). **The bound itself is not in the schema** — there is
  no CHECK constraint. It lives in the claim statement's third `WHERE` branch (`reservations.state =
  'held' AND reservations.customer_id = EXCLUDED.customer_id AND reservations.held_since > now() -
  interval '60 minutes'`), which is the branch that also *preserves* `held_since` via a `CASE`; every
  other admitted branch is a genuine reclaim and resets it. `BookingStore.MAX_HOLD_MINUTES = 60` is a
  documentation constant only — a `@Query` string is an annotation value and cannot interpolate a
  constant, so the Java constant and the SQL literal must be changed together. The refusal is **not
  instantaneous eviction**: a squatter's outstanding hold stays live until its own `expires_at`, so
  the seat returns to contention within at most one further TTL (15 min), via the expired branch or
  the sweep, whichever arrives first.

**SQL style: single-statement and validator-friendly.** Booking's seat claim is design-out via
`seat_id` as the primary key + `INSERT … ON CONFLICT … DO UPDATE … WHERE`; projections via
`INSERT … ON CONFLICT … DO UPDATE`. Pricing allocates each price version **atomically in one
statement**: `appendPrice` is a single `INSERT … SELECT … coalesce(max(version), 0) + 1 … FROM
price_events WHERE event_id=… AND tier=… RETURNING version`, guarded by a **full
`UNIQUE (event_id, tier, version)` index** (V003) — a concurrent double-allocation makes the loser's
insert fail visibly instead of appending a duplicate version (NOT a partial-index `ON CONFLICT`).

On CTEs: plain (read-only) CTEs validate fine in pg-codegen — the early "CTE alias not found" reports
were a cascade from an empty schema, not a CTE gap. **Data-modifying CTEs (`WITH … UPDATE …
RETURNING`) are unsupported** and now produce a clear located compile error rather than silent
mis-validation (§8.4). The single-statement style is a design preference here, not a workaround.

---

## 6. Resources & config (`src/main/resources/resources.toml`)
Every `@ResourceQualifier` config section a slice references is declared here (the blueprint generator
validates it at build time). `aether.toml`'s `[database]` block ships enabled by default; the live resource
config is `resources.toml`. The complete inventory, in file order — 17 sections:
- `[database]` with `async_url = postgresql://postgres:postgres@localhost:5432/forge` (+ `[database.pool_config]`).
- `[seat-sold]`, `[seat-released]`, `[price-changed]` — each `topic_name = "…"` (top-level sections, bare names).
  Kebab-case, and still required even though the topics are now typed `Topic<T>` constants (§3).
- **`[scheduling.sweep-holds]`** — the `Scheduled` resource behind `SweepHolds.sweep()`:
  `interval = "60s"`, `cron = ""`, `execution_mode = "SINGLE"`.
- `[http] base_url, timeout_ms` — the payment gateway (+ a **local stub gateway** for smoke test).
- `[notification] backend = "smtp"` + `[notification.smtp]` host/port/… (+ a **local SMTP sink** e.g. Mailpit on :1025).
- **Interceptor sections (§11)** — `[cache.availability.seat-status]`,
  `[cache.availability.sold-count]`, `[cache.quote.quote-for-customer]` (all `mode = "LOCAL"`), plus
  `[log.eventmanagement.mark-seat-sold]`, `[log.eventmanagement.mark-seat-released]`,
  `[log.availability.project-seat-sold]`, `[log.availability.project-seat-released]`,
  `[log.quote.project-price]`. **Kebab-case throughout, same as topics and scheduling** — the rc3
  codegen bug that once forced underscores here is fixed upstream and the workaround is gone (§8.9).
- Per-slice `src/main/resources/slices/<Name>.toml` `[blueprint] instances = N` (read slices: higher N)
  — 24 of them, one per slice.

---

## 7. Build / verify / smoke
`mvn clean install` green (slice-processor + pg-codegen, all schema validated; **214 unit tests**) →
`jbct check` clean (**0 errors, 0 warnings**) → `jbct-reviewer` per slice + Aether wiring check →
`jbct verify-slice`. Reaching zero warnings still needs **33 per-declaration `@SuppressWarnings`**
in `src/main/java`, carrying 53 rule tokens: **24 `JBCT-ORD-01`** (one per slice — see below),
**20 `JBCT-SEQ-01`** (the impl-record measurement artifact), **6 `JBCT-UC-02`** (the pub-sub
consumers and `SweepHolds`, whose trigger is a fact or a timer rather than a `Request`), and
**3 `JBCT-VO-01`** (the `shared.event` fact records). Twenty declarations carry SEQ-01 and ORD-01
together, which is why there are fewer sites than tokens. One further `@SuppressWarnings("unchecked")`
sits in `src/test` (`FakeGateway`) and is outside this count.

**`JBCT-ORD-01` cannot be satisfied while the slice contract holds.** The rule ranks a nested
`record` (0) ahead of a `static` factory (3), but the slice shape of §3 puts the implementation
record *inside* its own factory method — so the record can never precede the factory that lexically
contains it. No reordering of members removes the inversion: it is a conflict between the lint rule
and the shape the runtime requires, not a defect in the code. All 24 slices therefore suppress it at
the impl-record declaration, each with the reason on the line above. Filed as #18 in
`AETHER-WISHLIST.md`.

The VO-01 line is what remains of ~76 suppressions: rc2's shape-aware lint made most of the VO-01
suppressions on transport/row/fact records redundant, and they were removed.

**Formatting has a gap worth knowing:** the `jbct-maven-plugin` `format` goal binds to
`process-sources`, which covers `src/main/java` only — **test sources are never reformatted by a
build.** Run `jbct format src/test/java` explicitly or drift accumulates there unnoticed. The
`~/.jbct` CLI and the Maven plugin are the same `1.0.0-rc3` engine and emit byte-identical output.

Smoke on local Forge: `start-postgres` → bring up stub gateway + SMTP → `run-forge` → curl
open-event, set-price, buy, cancel, availability, quote.

## 8. Known tool issues surfaced (the "test our own tool" payoff)
1. **slice-processor** route-import collision — duplicate single-type imports for same-simple-name
   error types across packages (when this was hit, the book's idiomatic per-VO `Blank`/`Malformed`
   records plus a per-process `StoreUnavailable`; the same-simple-name pressure is unchanged today,
   now carried by the per-slice `ServiceUnavailable` enums).
   **FIXED** — merged via PR pragmaticalabs/pragmatica#364 (with a regression test) and **released in `slice-processor:1.0.0-rc2` on Maven Central (2026-07-16)**.
1b. **slice-processor** codec-gen shadowing — a generated `*Factory` references an injected slice's
   nested `Request`/`Response` by *simple* name, but the factory's local record `implements` the host
   slice, whose inherited member types `Request`/`Response` shadow them (JLS §6.5.5.2) → wrong type
   bound, compile errors. Guaranteed by "every slice has `Request`/`Response`" + "a slice injects
   another slice" (e.g. `BuyTicket` injecting `QuotePrice`/`SaleStatus`). **FIXED** — `FactoryClass
   Generator` now emits fully-qualified names in codec entries; regression test added. Same PR pragmaticalabs/pragmatica#364 as the route-import fix; **released in `slice-processor:1.0.0-rc2` on Maven Central (2026-07-16)**.
2. **pg-codegen** (rc1) emitted the generated SQL constant as a plain `"…"` literal and `escapeSql`
   escaped only `\` and `"`, never newlines — so a text-block `@Query("""…""")` produced an
   uncompilable literal, forcing single-line / `"…" + "…"` concatenated SQL.
   **FIXED in the released `1.0.0-rc2`** (verified 2026-07-17: escaped `\n` emission, named→positional
   params intact) — the stores now use text-block `@Query` throughout.
3. **pg-codegen** schema discovery used a hardcoded `MIGRATION_DESCRIPTIONS` guess-list
   (`init/base/seed/add_tables/…`) probed as `V%03d__<desc>.sql`, stopping after 3 consecutive
   misses — any migration whose description wasn't in the list (e.g. `V002__booking.sql`) was silently
   skipped and its tables read as "not found". The escape hatch was a `schema/migrations.list` manifest.
   **FIXED — `SchemaLoader` now globs `V*__*.sql` from the schema directory.** The manifest is
   advisory; **this repo no longer ships one** (it was deleted, and the build is green without it).
4. **CTEs — read-only yes, data-modifying no.** Plain CTEs validate fine; the early "CTE alias not
   found" reports were a cascade from an empty schema, not a CTE gap. **Data-modifying CTEs
   (`WITH … UPDATE … RETURNING`) are NOT supported** — since rc2 they produce a clear located compile
   error instead of silently mis-validating. The design prefers single-statement `ON CONFLICT`
   design-out anyway, so nothing here needs the unsupported shape.
4b. **pg-codegen validates an aggregate inside `INSERT … SELECT … RETURNING`.** The atomic price-version
   allocation `INSERT INTO price_events … SELECT …, coalesce(max(pe.version), 0) + 1 … FROM price_events
   pe WHERE … RETURNING version` type-checks cleanly — the `max(...) + 1` aggregate in the `SELECT` and
   the `RETURNING` projection both validate. This is how pricing allocates versions safely under the full
   `UNIQUE (event_id, tier, version)` index (§5); the race it would otherwise risk is closed by the index,
   not shipped as a known issue.
5. **slice-processor** HTTP route generator's `parameterType()` assumed a single method parameter — a
   slice method with 2+ params crashed route generation (`IllegalStateException`, truncated
   `*Routes.java`). rc2+ auto-generates a wrapper `<Method>Request` instead of crashing. The
   convention here is unchanged, because it is the idiomatic JBCT form anyway: **every slice method
   takes exactly one parameter**.
6. **`@Notify` and the interceptor factories ship in separate artifacts** —
   `org.pragmatica-lite.aether:resource-notification` and `:resource-interceptors`, neither in
   `resource-api`. Each must be added as its own `provided` dependency or the package won't resolve.
   **None of the three `resource-*` artifacts has ever been published to Maven Central** (§8.10). The
   real notification API is `Notification.Email.email(from, List<to>, subject, NotificationBody)` +
   `NotificationBody.Text.text(body)`, not a flat `notification(to,subject,body)`.
7. **`Promise.all` caps at 15 — still true in core; only *generated* wiring escapes it.** The
   slice-processor wires a factory's *transitive* dependencies through `Promise.all()`, whose largest
   core overload is `all(…)` → `Mapper15`. rc2 added `BatchedAll` **in the slice-processor's generator**,
   which chunks a generated factory's dependency list beyond 15 (fail-fast preserved) — so the cap no
   longer constrains slice wiring. **It is unchanged for code you write by hand**: `Promise.all` still
   has no 16-argument overload. Beware the shorthand "the 15-dep cap is gone"; the accurate statement
   is "the generator batches around it." The one-slice-per-use-case split predates the fix and stands
   on its own design merits (`BuyTicket` injects `QuotePrice`, `SaleStatus` and `SeatSellability`
   directly); cross-subsystem *facts* still propagate via pub-sub.
8. **No `@Heartbeat`, but `Scheduled` is real and adopted.** No `@Heartbeat` annotation exists in any
   rc (the skill lists it, the jar doesn't ship it). rc2+ does ship `Scheduled` — a zero-parameter
   `Promise<Unit>` method, interval or cron, KV-tracked — and **`SweepHolds` now uses it**
   (`sweep()` behind `@SweepSchedule`, 60s, §4.3). Adoption note: `@ResourceQualifier` is
   `@Target(ANNOTATION_TYPE)` only, so a `Scheduled` method needs a **custom wrapper annotation**,
   exactly like the pub-sub subscription qualifiers. The operator HTTP route is kept alongside so a
   sweep can still be forced by hand.
9. **THIRD codegen bug — a hyphen in an interceptor config path. FIXED upstream; workaround removed.**
   An interceptor `@ResourceQualifier(config = "cache.availability.seat-status")` generated an
   **illegal Java identifier**: `FactoryClassGenerator.collectUniqueInterceptors` built the lambda
   parameter name with `configSection().replace('.', '_')` as its *only* sanitization, so the hyphen
   survived and the emitted factory read
   `.map((store, methodInterceptor_cache_availability_seat-status) -> {`. javac reported
   `')' or ',' expected` **inside generated code**, with no `[SLICE-…]` diagnostic pointing at the
   slice. It stayed invisible until interceptors were adopted because hyphens work everywhere else:
   `Scheduled` never reaches the factory, and publisher/subscription qualifiers pass the hyphenated
   topic through as a *string literal*. The type half of the same identifier already went through
   `variableSafeName()` — only the config half was unguarded.
   **FIXED** — upstream commit `311a1b0d7` (#561) on `release-1.0.0-rc3`. Sanitization now lives in
   `ResourceQualifierModel.variableSafeConfigSection()`, which replaces every code point failing
   `Character.isJavaIdentifierPart` with `_` (identifier-*part*, not -*start*: the fragment is only
   ever appended after a `typeName_` prefix, so a leading digit is legal there). Sanitizing alone was
   not enough and was fixed in the same commit — the mapping is not injective (`a-b` and `a_b`
   collapse onto one identifier while `deduplicationKey()` still keeps them as two entries, so two
   interceptors differing only by separator declared the same lambda parameter twice), hence issued
   names are tracked and de-collided with a numeric suffix. Three regression tests cover the
   hyphenated section, the separator collision, and the pre-existing dotted form; the interceptor
   path had no fixture at all before, which is why it shipped. Only the local variable name changes —
   the `ctx.resources().provide(Type.class, "…")` literal still carries the section verbatim, so
   resolution behaviour and envelope structure are untouched.
   **Here:** the underscore workaround is gone; all eight interceptor sections are hyphenated again,
   so `resources.toml` is once more uniform with its topic and scheduling sections. Generated
   identifiers read `cacheMethodInterceptor_cache_availability_seat_status`, and all 8
   `intercept(impl::execute)` wirings are intact. This was the **third** codegen bug this project
   surfaced in the rc series — with items 1 and 1b, **all three are now fixed upstream**.
10. **The `aether/resource` subtree has never been published.** `resource-api`,
   `resource-notification` and `resource-interceptors` 404 on Maven Central on every rc line —
   `aether/resource/pom.xml` sets `<skipPublishing>true</skipPublishing>`, inherited by all the
   resource modules. Since every slice project needs them as `provided` compile deps, nothing that
   touches persistence, HTTP, notifications or interceptors builds from Central alone. On **rc3 the
   point is moot in the other direction**: rc3 is not on Central *at all*, so the whole dependency
   line comes from a local `mvn install` of `release-1.0.0-rc3`.

---

## 9. Validated slice conventions
Proven against the current toolchain — every slice MUST follow them:
- **One parameter per slice method.** A request `record` (path/body fields bind to its components by
  name) or a single primitive. The processor can now auto-wrap multi-param methods, but one request
  record stays the JBCT idiom.
- **`@PgSql` SQL uses `"""` text blocks throughout.** The rc1 mis-emit that forced single-line /
  concatenated SQL is fixed (§8.2). `ON CONFLICT … DO UPDATE … WHERE`, `RETURNING`, aggregates inside
  `INSERT … SELECT … RETURNING`, and unique and partial indexes all validate. **Read-only CTEs
  validate; data-modifying CTEs do not** (§8.4).
- **No migration manifest.** `V*__*.sql` is auto-discovered from the schema directory; this repo ships
  no `migrations.list`.
- **Pub-sub:** each fact has a pair of wrapper annotations in `shared.event` (§3) — mandatory, because
  `@ResourceQualifier` is `@Target(ANNOTATION_TYPE)` and cannot annotate a parameter or method
  directly. Publisher = `Publisher<Fact>` factory param + the `@…Publisher` PARAMETER annotation;
  subscriber = a method annotated `@…Subscription` returning `Promise<Unit>`. Subscriber methods need
  NO route. Topics are typed `Topic<T>` constants on the fact record. FQNs:
  `org.pragmatica.aether.slice.{Publisher,Subscriber}`, `org.pragmatica.aether.slice.topic.Topic`,
  `org.pragmatica.aether.slice.annotation.ResourceQualifier`.
- **Scheduling:** same wrapper-annotation pattern — a custom `@interface` meta-annotated with
  `@ResourceQualifier(type = Scheduled.class, config = "<section>")`, applied to a **zero-parameter**
  `Promise<Unit>` method that delegates to `execute(new Request())`.
- **Interceptors:** same wrapper-annotation pattern, `@Target(METHOD)`, applied to `execute`. **Config
  section names are kebab-case like every other section** — the generator sanitizes and de-collides
  them into the identifiers it emits (§8.9).
- **Resources:** `@Http org.pragmatica.aether.resource.http.HttpClient` (`postJson(url, body, Class<T>)`);
  `@Notify org.pragmatica.aether.resource.notification.NotificationSender` (`send(Notification)`).
- **Direct inter-slice call:** inject the callee interface as an unannotated factory param.
- **Facts** carry only String/long (parsed back into VOs on receipt) to keep wire codecs trivial.
- **Combinators:** `Result.all(...).map(...)`, `Result.async()`, `Option.async(Cause)`,
  `Promise.{map,flatMap,mapError,recover}`, `Promise.{success,failure,UNIT}`, `Option.{option,present,empty}`.
- Slice factory: `static Iface iface(@PgSql Store s, …) { record iface(…) implements Iface {…} return new iface(…); }`
  — factory name = lowercase-first interface name, distinct from every method name.

---

## 10. As-built deltas (where the implementation refined the design)
The build is green: **24 single-use-case slices** (19 routed), **214 unit tests**, blueprint +
`verify-slice` pass on **`1.0.0-rc3`** — a local build of `release-1.0.0-rc3`, since rc3 is not on
Maven Central and the three `resource-*` artifacts have never been published anywhere (§8.10). The
structure is the **PFD telescope as packages**: system → subsystem → workflow → use case, each use
case one slice (`Request`/`Response` + `execute(Request)`), write subsystems sharing one `@PgSql`
store, read subsystems segregated per use case. Deliberate refinements vs §1–§7:

- **One use case per slice; synchronous cross-subsystem reads.** `BuyTicket` injects `QuotePrice`,
  `SaleStatus` and `SeatSellability` directly (the book's authoritative synchronous read).
  Cross-subsystem *facts* stay events (`SeatSold`/`SeatReleased`/`PriceChanged` with
  `convergence`/`projection` consumer slices). `EventOpened` was dropped (no consumer once booking
  reads synchronously). *(An earlier iteration made booking event-driven with local projections to
  dodge the `Promise.all` 15-cap; the slice split removed that pressure, and rc2's generator-side
  batching removed the cap's reach over generated wiring entirely — §8.7.)*
- **`SeatSellability` was added to make `BlockSeat` mean something.** The `seats` table is owned by
  event-management, so booking cannot read it; without a seam, blocking a seat did not stop holds or
  purchases. The new slice is that seam (§4.1).
- **The seat, not a surrogate, is the reservation identity.** `reservations.seat_id` is the primary
  key and `claim_id` is a rotating non-key column; the `bookings → reservations` FK is gone (V007,
  §5). This unblocked reselling a cancelled seat and converting a hold into a purchase — both of
  which previously failed permanently.
- **Seat facts carry a version and both downstream writers guard on it** (V008, §5) — the availability
  projection by `version < EXCLUDED.version` on an upsert, the authoritative `seats` row by a guarded
  `UPDATE` that also asserts the expected prior state.
- **Hold lifetime is bounded** (V009, §5): the owner may refresh their own hold, but only within 60
  minutes of `held_since`, so a client re-acquiring on a timer cannot squat a seat forever.
- **`CancelTicket` is refund-first and re-drivable** — money back before any observable state change;
  re-driving a failed attempt converges, a second successful call returns
  `StateConflict.ALREADY_CANCELLED` (§4.3).
- **Buy by `seat` id, not section/row/number.** The client picks a `SeatId` (from availability) and
  passes `(customer, event, seat, tier)`; the seat structure is created in event-management.
- **Event-management uses scalar methods** — every `Request` is scalar-only (trivial codecs).
- **Hold sweep is `Scheduled`** (60s), with the operator HTTP route kept alongside (§4.3, §8.8).
- **`Percent` value object + `Money` non-negative by construction.** Demand scaling goes through
  `Money.scaledByPercent(Percent)` with a strictly-positive `Percent`, so a non-positive scale is
  rejected up front and `Money`'s non-negativity invariant (held at every construction boundary, since a
  record's canonical ctor can't return a `Result`) needs no extra guard on the result.
- **Pricing allocates price versions atomically.** `appendPrice` is one
  `INSERT … SELECT coalesce(max(version),0)+1 … RETURNING version` under a full
  `UNIQUE (event_id, tier, version)` index (§5) — a concurrent double-allocation fails the loser's insert
  visibly instead of corrupting history.
- **`BuyTicket` writes the `bookings` row LAST.** The ticket and payment rows (no FK to bookings) are
  inserted first; the booking row — the only partial that `activeBookingCount` and `CancelTicket` read —
  goes last, so any partial store failure precedes it and the confirm-step compensation
  (void + release) reverses cleanly with no orphaned confirmed booking.
- **`CreateEvent` parses `onSaleAt` → `IsoDateTime`** (failure `MalformedOnSaleAt`), so a blank/garbage
  on-sale timestamp can no longer be persisted verbatim.
- **Every route declares `[security]`, and every `[errors]` block is `strict`** (§12).

---

## 11. Interceptors — what attached, and what could not
Interceptors are the runtime's realization of PFD's **Aspects** pattern. Eight are wired, all via a
custom `@Target(METHOD)` wrapper annotation on the slice's `execute`:

| Interceptor | Count | Slices | Config |
|---|---|---|---|
| `CacheMethodInterceptor` | 3 | `SeatStatus`, `SoldCount`, `QuoteForCustomer` | `mode = "LOCAL"` |
| `LoggingMethodInterceptor` | 5 | `MarkSeatSold`, `MarkSeatReleased`, `ProjectSeatSold`, `ProjectSeatReleased`, `ProjectPrice` | `log_result` per slice |

The caches are **`LOCAL`, i.e. per-instance and unreplicated**: two instances of a read slice can
serve different answers for the same seat until their entries expire. That is acceptable precisely
because these reads are already bounded-staleness projections — the cache widens an existing window
rather than introducing a new guarantee.

**What the logging interceptors actually earn.** On the three `availability`/`quote` projections the
interceptor **only ever observes `Success`**, because those slices `.recover(_ -> unit())` *inside*
`execute` — the swallowed cause never reaches the interceptor. So there it earns arrival and latency,
not error visibility, and `log_result = false` reflects that. Only the two `eventmanagement`
convergence consumers, which propagate typed causes (§4.1), log a real outcome.

### Payment retry / circuit-breaking: evaluated and NOT adopted
The obvious candidate — wrapping the `@Http` payment gateway in retry + a circuit breaker — is **not
adoptable as an aspect here**, for two independent reasons:

1. **Wrong attachment point.** Interceptors attach only to `@Slice` interface methods. The gateway
   calls are *private helpers* inside `BuyTicket`/`CancelTicket`, so the only attachable method is
   `execute` — the entire saga. Retrying `execute` would re-run seat claiming and confirmation, not
   just the payment call.
2. **The breaker would trip on the designed outcome.** `CircuitBreakerInterceptorFactory` builds its
   breaker with `.withDefaultShouldTrip()`, and core's default is literally `shouldTrip(_ -> true)` —
   *every* typed `Cause` counts toward the failure threshold. `CircuitBreakerConfig` exposes only
   `failureThreshold`, `resetTimeout` and `testAttempts`, with **no way to supply a predicate**. On a
   hot event, `StateConflict.SEAT_UNAVAILABLE` — which is the *intended* result of the contended-seat design-out, not
   a fault — would count as failure and open the breaker, taking down the buy path precisely when it
   is working as designed.

The correct fix is structural, not configurational: extract the gateway into its own **`PaymentGateway`
slice**, whose `execute` *is* the payment call. Then retry and circuit-breaking attach at the right
granularity, and the breaker sees only gateway causes.

Two further provisioning gaps on rc3: **`RetryConfig` carries a `BackoffStrategy` object** and
**`MetricsConfig` carries a micrometer `MeterRegistry`** — neither is expressible in a TOML config
section, so retry and metrics interceptors cannot be provisioned declaratively at all.

### Declarative stream consumers: evaluated and DEFERRED
Migrating the pub-sub consumers to rc3's declarative stream consumers was assessed and deliberately
deferred: rc3's own tests disclaim cross-node failover coverage, `@PartitionKey` is a no-op for a
topic `Publisher`, and the cursor advance is an unconditional set (so a failed handler still advances
past its message). None of that improves on the current at-most-once story enough to justify the move.

---

## 12. Validation → HTTP status
Malformed input returns **400** (or **422** for well-formed-but-unacceptable values) across the API.
Getting there took two coordinated pieces, because the default behaviour was a 500 for every
validation failure:

1. **The generated router only matches causes declared in the slice's own package.** `[errors]`
   patterns are globs over *simple names*, but the generated `switch` arms are built from the slice's
   own `Cause` types; a shared value-object failure such as `SeatLocation.Error.Invalid.BLANK_SECTION` matches
   no arm and falls through to `default -> INTERNAL_SERVER_ERROR`. **Every slice therefore restates
   shared VO failures as its own typed cause** — typically `InvalidRequest(field, detail)` → 400 and
   `UnacceptableValue(field, detail)` → 422 — by switching over the shared failure in its `valid…()`
   factory.
2. **`Result.all` wraps even a single failure in `Causes.CompositeCause`**, a core type outside every
   slice's package, which likewise reaches the router unmatched. `shared/Validation.firstFailure`
   unwraps it (`Option.from(cause.stream().findFirst()).or(cause)`) and is a no-op on an
   already-unwrapped cause. Used in 8 slices.

All 19 routed slices set **`strict = true`** and a `default = 500`; 18 map something to `HTTP_400`
and 6 to `HTTP_422`. `strict` makes an unmapped `Cause` a **build failure**, which is what keeps the
restating discipline from silently rotting.

**Route security** is likewise declared everywhere — 8 `role:admin` (event-management and pricing
writes), 4 `authenticated` (customer booking operations), 1 `role:operator` (the hold sweep), 6
`public` (the read/query slices plus the seat-sellability lookup). Before this, every endpoint was
implicitly public, including the entire admin write surface.

---

## 13. `@PgSql` conventions worth remembering
- **Convention methods work without `@Query`:** `findBy…` / `countBy…` / `existsBy…` / `deleteBy…`
  derive SQL from the method name (grammar: `And` only — no `Or` — plus `OrderBy…Asc/Desc` and the
  usual operator suffixes). **The crux:** a record-returning finder infers its table from the **row
  record's name** (strip `Row` → snake_case → match the schema), so `findBySeatId` needs no
  annotation — but a **scalar** return (`Promise<Long>`/`Boolean`/`Unit`) has no row record and
  therefore **requires `@Table(XxxRow.class)`** (a `Class<?>`, not a string). A scalar `countBy`
  needing `@Table` is a DX gap worth filing — it could infer the entity type the way Spring Data does.
- **Repository-pattern parity.** Aether `@PgSql` is closest to Spring Data: an interface, derived
  methods plus `@Query`, framework-generated implementation, no hand-written mapper. The two
  meaningful deltas are `Promise`/`Option` (async, functional) instead of blocking `Optional`, and
  **compile-time** SQL validation instead of startup-time or none.
- Row records are plain records whose component names must equal the column names; a rename touches
  the column, the `@Query`, the row field and the bind parameter in lockstep.

---

## 14. PFD question altitudes (for the next design pass)
Where the design questions actually live, so they are not re-derived:
- The **use-case altitude** forces roughly 15 questions — `book-pfd/spiral-0-decisions.md` is the
  five-question warm-up, `spiral-1-use-case.md` the full set. About 12 of them are **business**-facing,
  not technical.
- Higher altitudes add their own: **workflow** — cancellation / hold / residual policy, per-data-class
  consistency, idempotency; **subsystem** — audit, KYC, tax and other cross-cutting concerns;
  **system** — few.
- The canonical "ask the business" list is **Phase 4's eleven-question table** in
  `architecture-synthesis.md` (latency / throughput / availability / consistency / durability ·
  compliance and mandates · deployment and cost · multi-X and scale), asked at three scopes: **per use
  case, per data class, per domain**.
