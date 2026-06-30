# Ticketing Platform — Design (PFD → JBCT → Aether)

Reference implementation of the event-ticketing example threaded through the PFD book
(`book-pfd/spiral-1..4`, `architecture-synthesis.md`). This is the **enterprise profile**'s
process design, realized as Aether slices on the unified runtime. Deployment-profile concerns the
book describes (multi-region, distributed store, polyglot, geo-scaled read tier) are noted where
they would attach but are not physically built — one local slice project embodies the **process
designs**, not the global topology.

Base package: `org.pragmatica.example.ticketing`.

---

## 1. Topology — five subsystems on one runtime (23 single-use-case slices)

Each row is a **subsystem**; the runtime composes the single-use-case slices inside it (in-process or
across nodes). Slice counts: eventmanagement 9, pricing 3, booking 5, availability 4, quote 2 = **23**.

| Subsystem | Kind | Owns (write) | Recovery | Substrate role |
|-------|------|--------------|----------|----------------|
| `eventmanagement` | core | events, seats (structure), event audit | BER (capacity) + design-out (seat convergence) | subscribes `SeatSold`/`SeatReleased`; direct: `saleStatus` read |
| `pricing` | core | price_events (append log), current_price projection | design-out (append-only, idempotent) | publishes `PriceChanged`; direct: `quote` read (authoritative, for booking) |
| `booking` | core | reservations, bookings, payments, tickets, booking audit | **BER** saga + **FER** holds + design-out claim | publishes `SeatSold`/`SeatReleased`; direct→`pricing.quote`, `eventmanagement.saleStatus`; `@Http` payment; `@Notify`; HTTP `SweepHolds` sweep |
| `availability` | read (split, scaled) | seat_availability projection | design-out (idempotent convergence) | subscribes `SeatSold`/`SeatReleased` |
| `quote` | read (split, scaled) | price_view projection | design-out | subscribes `PriceChanged` |

**All six PFD patterns appear:** Leaf (VO factories, adapters), Sequencer (saga bodies),
Fork-Join (check-selling ∥ check-eligibility), Condition (cancellable?, hold decay state),
Iteration (expired-hold/block sweep), Aspects (declared compensation, uniform observability).

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
pub-sub is one-type-per-topic, no ordering/replay — fine, all consumers are idempotent).

| Fact record | Fields | Topic (`config`) | Publisher | Subscribers |
|-------------|--------|------------------|-----------|-------------|
| `SeatSold` | seatId, eventId, bookingId | `seat-sold` | booking | eventmanagement, availability |
| `SeatReleased` | seatId, eventId | `seat-released` | booking | eventmanagement, availability |
| `PriceChanged` | eventId, seatId, tier, amountMinor, currency, version | `price-changed` | pricing | quote |

Facts carry primitive/string-ish fields (UUID strings, longs) to keep wire codecs trivial; the
slices parse them back into VOs on receipt. (Verify on first build whether codec-gen accepts VO
fields directly; if so, richen them.)

Qualifier convention (per Aether pub-sub): each fact has a pair of **dedicated wrapper annotations**
centralized in `shared.event`, not raw per-slice `@ResourceQualifier`. The publisher annotation (e.g.
`@SeatSoldPublisher`) targets a PARAMETER and wraps `@ResourceQualifier(type = Publisher.class,
config = "seat-sold")`; the subscription annotation (e.g. `@SeatSoldSubscription`) targets a METHOD
and wraps `@ResourceQualifier(type = Subscriber.class, config = "seat-sold")` — same bare config
string. Slices reference the wrappers; the topic strings live in one place.

**Direct (synchronous) inter-slice calls** — inject the callee slice interface as an unannotated
factory parameter (processor generates the proxy):
- `BuyTicket` factory takes `QuotePrice quotePrice` → `quotePrice.execute(...)` during Buy
  (authoritative price).
- `BuyTicket` factory takes `SaleStatus saleStatus` → `saleStatus.execute(...)` during Buy (the book's
  "synchronous sale-status check, a read not a command").

---

## 4. Slice designs (six-property processes)

### 4.1 `eventmanagement` (9 slices)
Owns the venue/seat structure and event lifecycle. current-state + audit-as-data. Lifecycle
`CreateEvent`→`OpenEvent`→`CancelEvent`, capacity `AddSeat`/`BlockSeat`/`ReleaseSeat`, the `SaleStatus`
read, and the convergence consumers `MarkSeatSold`/`MarkSeatReleased`.

**CreateEvent** — trigger: operator HTTP. in: `{venue, onSaleAt}`. out: `{event}`. failures:
`BlankVenue`, `MalformedOnSaleAt`, `StoreUnavailable`. steps: validate venue (non-blank) **and parse
`onSaleAt` → `IsoDateTime`** together via `Result.all` → insert event in `'draft'` → respond. (No fact
published; a garbage timestamp can no longer be persisted verbatim.)

**OpenEvent** — trigger: operator HTTP. in: `{event}`. out: `{event}`. failures: `EventNotFound`,
`AlreadyOpen`, `StoreUnavailable`. steps: validate → ensure event exists → guarded
`UPDATE … SET status='on_sale'` (no fact published).

**CancelEvent** — trigger: operator HTTP. in: `{event}`. out: `{event}` (withdrawn). failures:
`EventNotFound`, `StoreUnavailable`. steps: validate → guarded `UPDATE … SET status='cancelled'
RETURNING` → respond.

**AddSeat** — trigger: operator HTTP. in: `{event, section, row, number, tier}`. out: `{seat}`.
failures: `EventNotFound`, `StoreUnavailable`. steps: validate (`EventId` + `SeatLocation` + `PriceTier`
via `Result.all`) → ensure event exists → insert seat in `'available'` → respond.

**BlockSeat** / **ReleaseSeat** (BER, defined inverses) — trigger: operator HTTP. in: `{seat}`. out:
`{seat}`. guarded transitions: block flips `'available'→'blocked'` (failure `SeatUnavailable`), release
flips `'blocked'→'available'` (failure `SeatNotBlocked`); both also surface `StoreUnavailable`.

**SaleStatus(event)** — direct read for booking (also HTTP-routed). in: `{event}`. out:
`{event, onSale, onSaleAt}` (`onSale` = status is `'on_sale'`). failures: `EventNotFound`,
`StoreUnavailable`.

**MarkSeatSold / MarkSeatReleased** — pub-sub subscribers (`@SeatSoldSubscription` /
`@SeatReleasedSubscription` on `execute(SeatSold)` / `execute(SeatReleased)`, no HTTP route):
idempotent `UPDATE seats SET state` to `'sold'` / `'available'` to keep the authoritative seat state
converged; a poison fact is recovered to `Unit` so delivery never wedges. design-out (safe to replay).

Tables: `events(id, venue, on_sale_at, status)` — status ∈ draft|on_sale|cancelled; `seats(id,
event_id, section, seat_row, number, tier, state)` — state ∈ available|blocked|sold|withdrawn;
`event_audit(id, event_id, kind, at, detail)`.

### 4.2 `pricing` (3 slices)
Append-only price history (design-out, "event-sourced-flavored") + a current_price projection.

**SetPrice** — trigger: operator HTTP. in: `{event, tier, amount, currency}`. out: `{version}`.
failures: VO validation (`EventId`/`PriceTier`/`Money`), `StoreUnavailable`. steps: validate →
**append** row to `price_events` (version allocated atomically; see §5) → upsert `current_price`
projection → **publish `PriceChanged`** → respond. recovery: design-out (correction = a new append at a
higher version, never an overwrite).

**AdjustPrice** — trigger: operator/internal HTTP. in: `{event, tier, percent}` (a `long`, 110 = +10%).
out: `{version}`. failures: `PriceNotFound`, `StoreUnavailable` (and VO validation, incl. a non-positive
`Percent`). steps: read current price → scale via **`Money.scaledByPercent(Percent)`** → append new
version → upsert → publish → respond. recovery: design-out.

**QuotePrice** — authoritative read used by booking (direct; also HTTP-routed). in: `{event, tier}`.
out: `{event, tier, amountMinor, currency, version}`. failures: `PriceNotFound`, `StoreUnavailable`.

Tables: `price_events(id, event_id, seat_id NULL, tier, amount_minor, currency, version, created_at)`,
`current_price(scope_key PK, event_id, seat_id NULL, tier, amount_minor, currency, version, updated_at)`.

### 4.3 `booking` (5 slices — the centerpiece)
Owns reservations/holds, bookings, payments, tickets. current-state + audit-as-data.

**BuyTicket** — the BER saga. trigger: customer HTTP POST. in: `{customer, event, seat, tier}`. out:
`{booking, ticket, seat, receipt, amountMinor, currency}`. failures (closed set): `SeatUnavailable`,
`EventNotSelling`, `CustomerIneligible`, `PriceUnavailable`, `PaymentDeclined`,
`PaymentProviderUnavailable`, `StoreUnavailable`.
steps (Sequencer + Fork-Join):
1. validate → `ValidBuy` (VOs via `Result.all`)
2. **Fork-Join**: `saleStatus.execute(event)` ∥ count customer's active bookings — join gates on
   selling + eligibility
3. `quotePrice.execute(event, tier)` → price (direct)
4. **reserve** — design-out claim: a single guarded `INSERT … ON CONFLICT (seat_id) DO UPDATE … WHERE`
   over a plain unique index on `seat_id`; a live hold/booking yields zero rows = `SeatUnavailable`,
   while a stale/expired hold is reclaimed inline
5. **authorize payment** — `@Http` gateway `POST /authorize` → `AuthResult{approved, receiptId}`;
   declined → `PaymentDeclined` (no field), transport failure → `PaymentProviderUnavailable`
   (compensate: release reservation)
6. **confirm** — guarded `UPDATE reservations SET state='confirmed'`, then insert `tickets(issued)`,
   `payments(authorized)`, and the `bookings` row **LAST** (so any partial store failure precedes the
   confirmed booking)
7. **notify** — `@Notify` best-effort (**FER**: `.recover` so a notify failure does not fail the buy)
8. **publish `SeatSold`**; respond.
compensation (declared, BER): a failure after step 4 releases the reservation; a failure in step 6
voids the authorization (`@Http`) and releases the reservation, then re-raises the original cause.
Inverses stay in-domain.

**CancelTicket** — saga + compensation. trigger: customer HTTP. in: `{booking, customer}`. out:
`{booking, receipt}`. failures: `BookingNotFound`, `NotOwner`, `AlreadyCancelled`, `RefundFailed`,
`StoreUnavailable`.
steps: validate → load booking → ensureCancellable (Condition; pure — owner, then not-cancelled) →
cancel booking row → cancel reservation by seat → refund (`@Http`) → **invalidate ticket** (sequential
Leaf — no Fork-Join, no `@Notify`) → **publish `SeatReleased`** → respond. recovery: BER.

**Hold** (FER time-as-decay) — `AcquireHold`, `CheckHold`. `AcquireHold` is the same design-out claim
as reserve, with a 15-minute TTL (state `'held'`); the loser of a contended seat fast-fails
`SeatUnavailable` (failures: `SeatUnavailable`, `StoreUnavailable`). `CheckHold` reports the hold's
decay as a **String label** `FRESH` / `STALE` / `EXPIRED` / `NONE`, computed at read from `expires_at`
(a Condition over persisted state — the book's FER; **no** sealed `HoldState` type). A missing row
reads as `NONE`; the only failure is `StoreUnavailable`.

**SweepHolds** — operator/cron HTTP `POST /holds/sweep` (rc1 has no `@Heartbeat`); the empty `Request`
record keeps the one-parameter contract. Iteration — expire held-but-stale rows (`RETURNING` the freed
seats), then publish `SeatReleased` per freed seat; out `{released}`. recovery: FER (forward progress;
nothing to compensate).

Tables: `reservations(id, seat_id, event_id, customer_id, state, expires_at, created_at)` with a
**plain unique index** `(seat_id)` (one reservation row per seat) = the design-out serialization point;
the claim's `INSERT … ON CONFLICT (seat_id) DO UPDATE … WHERE` reclaims only a cancelled/expired row;
`bookings(id, reservation_id, seat_id, event_id, customer_id, status, ticket_id)`;
`payments(id, booking_id, status, receipt_id, amount_minor, currency)`; `tickets(id, booking_id,
seat_id, status)`; `booking_audit(id, booking_id, kind, at, detail)`.

### 4.4 `availability` (read, split, scaled separately)
Subscribes `SeatSold` / `SeatReleased` → maintains the `seat_availability` projection (idempotent
upsert, via `ProjectSeatSold` / `ProjectSeatReleased`). Exposes **`SoldCount(event)`** (out
`{event, sold}`) and **`SeatStatus(seat)`** (out `{seat, state}`; a seat with no projection row reads
as `available`) — bounded-staleness reads. Own blueprint with higher `instances` — demonstrates the
read-path split and independent scaling. design-out (idempotent convergence). Table: `seat_availability(seat_id PK,
event_id, state, hold_expires_at, updated_at)`. **Persistence is segregated per use case** (no slice
needs >1 method): `SeatStatusStore` (`findStatus`), `SoldCountStore` (`countSold`), and a shared
`SeatProjectionStore` (`upsertStatus`) for the two projections — interface segregation, not one
subsystem store. State values flow through the shared `SeatState` enum (`SeatState.SOLD.dbValue()`
etc.); the `@Query` literals stay strings (validated against the TEXT column).

### 4.5 `quote` (read, split, scaled separately)
Subscribes `PriceChanged` → `price_view` projection (via `ProjectPrice`). Exposes the customer-facing
**`QuoteForCustomer`** read, in `{event, tier}` → out `{event, tier, amountMinor, currency, version}`
(distinct from pricing's authoritative `QuotePrice` used by booking). Own blueprint, higher instances.
Table: `price_view(scope_key PK, event_id, seat_id NULL, tier, amount_minor, currency, version, updated_at)`.
**Persistence is segregated per use case**: `QuoteViewStore` (`findByScope`) for the read,
`PriceProjectionStore` (`upsertPrice`, monotonic) for the projection.

---

## 5. Schema migrations (`src/main/resources/schema/`)
Sequential, idempotent, double-underscore, one per subsystem: `V001__initial.sql` (an empty
placeholder — no shared DDL), `V002__eventmanagement.sql`, `V003__pricing.sql`, `V004__booking.sql`,
`V005__availability.sql`, `V006__quote.sql`. **SQL style: single-statement, validator-friendly, no
CTEs** — booking's seat claim is design-out via a **plain unique index** on `seat_id` +
`INSERT … ON CONFLICT … DO UPDATE … WHERE`; projections via `INSERT … ON CONFLICT … DO UPDATE`.
Pricing allocates each price version **atomically in one statement**: `appendPrice` is a single
`INSERT … SELECT … coalesce(max(version), 0) + 1 … FROM price_events WHERE event_id=… AND tier=…
RETURNING version`, guarded by a **full `UNIQUE (event_id, tier, version)` index** (V003) — a concurrent
double-allocation makes the loser's insert fail visibly instead of appending a duplicate version (NOT a
partial-index `ON CONFLICT`). (pg-codegen rc1 does not validate CTE aliases and mis-emits multi-line
literals — see §8; the single-statement style is both safer and a cleaner design-out.)

---

## 6. Resources & config (`src/main/resources/resources.toml`)
Every `@ResourceQualifier` config section a slice references is declared here (the blueprint generator
validates it at build time). `aether.toml`'s `[database]` block ships commented out; the live resource
config is `resources.toml`.
- `[database]` with `async_url = postgresql://postgres:postgres@localhost:5432/forge` (+ `[database.pool_config]`).
- `[seat-sold]`, `[seat-released]`, `[price-changed]` — each `topic_name = "…"` (top-level sections, bare names).
- `[http] base_url, timeout_ms` — the payment gateway (+ a **local stub gateway** for smoke test).
- `[notification] backend = "smtp"` + `[notification.smtp]` host/port/… (+ a **local SMTP sink** e.g. Mailpit on :1025).
- Per-slice `src/main/resources/slices/<Name>.toml` `[blueprint] instances = N` (read slices: higher N).

---

## 7. Build / verify / smoke
`mvn clean install` green (slice-processor + pg-codegen, all schema validated) → `jbct check` clean
(**0 errors, 0 warnings**: the JBCT-VO-01 transport/row/fact records and JBCT-SEQ-01 impl-record
chains are verified false-positives, suppressed per-declaration via `@SuppressWarnings`) →
`jbct-reviewer` per slice + Aether wiring check → `jbct verify-slice`. Smoke on local Forge:
`start-postgres` → bring up stub gateway + SMTP → `run-forge` → curl open-event, set-price, buy,
cancel, availability, quote.

## 8. Known tool issues surfaced (the "test our own tool" payoff)
1. **slice-processor** route-import collision — duplicate single-type imports for same-simple-name
   error types (the book's idiomatic per-VO `Blank`/`Malformed` + per-process `StoreUnavailable`).
   **FIXED** — rc1 jar patched and **merged into `release-1.0.0-rc2` via PR pragmaticalabs/pragmatica#364** (with a regression test).
1b. **slice-processor** codec-gen shadowing — a generated `*Factory` references an injected slice's
   nested `Request`/`Response` by *simple* name, but the factory's local record `implements` the host
   slice, whose inherited member types `Request`/`Response` shadow them (JLS §6.5.5.2) → wrong type
   bound, compile errors. Guaranteed by "every slice has `Request`/`Response`" + "a slice injects
   another slice" (e.g. `BuyTicket` injecting `QuotePrice`/`SaleStatus`). **FIXED** — `FactoryClass
   Generator` now emits fully-qualified names in codec entries; regression test added. **Merged into `release-1.0.0-rc2` via PR pragmaticalabs/pragmatica#364** — the same PR as the route-import fix.
2. **pg-codegen** emits the generated SQL constant as a plain `"…"` literal and `escapeSql` escapes
   only `\` and `"`, never newlines — so a text-block `@Query("""…""")` produces an uncompilable
   literal. Workaround: single-line / `"…" + "…"` concatenated SQL, no embedded newlines.
   (real bug; fix candidate — escape `\n`, or emit a Java text block.)
3. **pg-codegen** schema discovery uses a hardcoded `MIGRATION_DESCRIPTIONS` guess-list
   (`init/base/seed/add_tables/…`) probed as `V%03d__<desc>.sql`, stopping after 3 consecutive
   misses — any migration whose description isn't in the list (e.g. `V002__booking.sql`) is silently
   skipped and its tables read as "not found". Escape hatch: a `schema/migrations.list` manifest
   (one bare filename per line) bypasses the probe. **We use the manifest.** (footgun; the probe
   should fall back to listing the directory or require the manifest.)
4. CTEs — including data-modifying `WITH … UPDATE … RETURNING` — ARE supported by the
   parser/validator. The earlier "CTE alias not found" was a cascade from the empty schema, not a
   CTE gap. (Verify the data-modifying shape on first green build; the design still prefers
   single-statement `ON CONFLICT` design-out for the split-ownership model.)
4b. **pg-codegen validates an aggregate inside `INSERT … SELECT … RETURNING`.** The atomic price-version
   allocation `INSERT INTO price_events … SELECT …, coalesce(max(pe.version), 0) + 1 … FROM price_events
   pe WHERE … RETURNING version` type-checks cleanly — the `max(...) + 1` aggregate in the `SELECT` and
   the `RETURNING` projection both validate. This is how pricing allocates versions safely under the full
   `UNIQUE (event_id, tier, version)` index (§5); the race it would otherwise risk is closed by the index,
   not shipped as a known issue.
5. **slice-processor** HTTP route generator's `parameterType()` assumes a single method parameter — a
   slice method with 2+ params crashes route generation (`IllegalStateException`, truncated
   `*Routes.java`). The factory/codec generator handles multi-param fine; only routing breaks.
   Workaround = the idiomatic JBCT form anyway: **every slice method takes exactly one parameter**.
6. **`@Notify` ships in a separate artifact** `org.pragmatica-lite.aether:resource-notification` —
   NOT in `resource-api`. It must be added as its own `provided` dependency, or the
   `org.pragmatica.aether.resource.notification.*` package won't resolve. (`@Http`/`HttpClient` ARE
   in `resource-api`.) The real notification API is `Notification.Email.email(from, List<to>, subject,
   NotificationBody)` + `NotificationBody.Text.text(body)`, not a flat `notification(to,subject,body)`.
7. **slice factory dependency cap = 15.** The slice-processor wires a factory's *transitive*
   dependencies through `Promise.all()` (max arity 15). A slice that directly injects other slices
   pulls their dependency subtrees in, so a few direct slice injections blow the cap
   ("Too many dependencies (N) for Promise.all()"). The one-slice-per-use-case split keeps each
   factory's transitive deps well under 15, so synchronous cross-slice injection for reads is viable
   (`BuyTicket` injects `QuotePrice` + `SaleStatus` directly); cross-subsystem *facts* still propagate
   via pub-sub (subscribe to facts, keep local projections) — the book's "events across subsystems" substrate.
8. **No `@Heartbeat`** annotation exists in rc1 (the skill lists it, the jar doesn't ship it). The
   hold-expiry sweep is therefore an operator-triggered endpoint; production would wire a scheduled
   trigger.

---

## 10. As-built deltas (where the implementation refined the design)
The build is green (**23 single-use-case slices**, 122 unit tests, blueprint + `verify-slice` pass on
**1.0.0-rc2**). The structure is the **PFD telescope as packages**: system → subsystem → workflow → use case,
each use case one slice (`Request`/`Response` + `execute(Request)`), slices in a subsystem sharing one
`@PgSql` store. A few deliberate refinements vs §1–§7, driven by rc1 reality (§8) or simplicity:
- **One use case per slice; synchronous cross-subsystem reads.** `BuyTicket` injects the `QuotePrice`
  and `SaleStatus` slices directly (the book's authoritative synchronous read) — viable because the
  split drops every factory's transitive dep count well under the 15-cap (§8.7). Cross-subsystem
  *facts* stay events (`SeatSold`/`SeatReleased`/`PriceChanged` with `convergence`/`projection`
  consumer slices). `EventOpened` was dropped (no consumer once booking reads synchronously).
  *(An earlier iteration made booking event-driven with local projections to dodge the 15-cap before
  the slice split removed that constraint.)*
- **Buy by `seat` id, not section/row/number.** The client picks a `SeatId` (from availability) and
  passes `(customer, event, seat, tier)`; the seat structure is created in event-management.
- **Event-management uses scalar methods** (`CreateEvent`→`AddSeat`→`OpenEvent`, capacity, etc.) —
  every `Request` is scalar-only (trivial codecs).
- **Design-out claim** uses a plain unique index on `seat_id` + a conditional `ON CONFLICT … DO UPDATE
  … WHERE` "steal" (reclaims expired holds inline) rather than a partial-index conflict target.
- **Hold sweep** is `SweepHolds` (`POST /holds/sweep`, operator/cron), since rc1 has no `@Heartbeat`.
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
  goes last, so any partial store failure precedes it and the confirm-step BER compensation
  (void + release) reverses cleanly with no orphaned confirmed booking.
- **`CreateEvent` parses `onSaleAt` → `IsoDateTime`** (failure `MalformedOnSaleAt`), so a blank/garbage
  on-sale timestamp can no longer be persisted verbatim.

---

## 9. Validated slice conventions (confirmed by the `quote` template build)
These are proven against rc1 — every slice MUST follow them:
- **One parameter per slice method.** A request `record` (path/body fields bind to its components by
  name) or a single primitive (like HelloWorld's `greet(String name)`). Never 2+ params.
- **`@PgSql` SQL is single-line / `"…" + "…"` concatenated, never a `"""` text block.** `ON CONFLICT
  … DO UPDATE … WHERE`, `RETURNING`, and unique and partial indexes all validate. No CTEs.
- **`migrations.list`** must list every `V0NN__*.sql` (pg-codegen's filename probe misses our names).
- **Pub-sub:** each fact has a pair of wrapper annotations in `shared.event` (§3). Publisher =
  `Publisher<Fact>` factory param + the `@…Publisher` PARAMETER annotation (wraps
  `@ResourceQualifier(type=Publisher.class, config="<bare-topic>")`); subscriber = a method annotated
  `@…Subscription` (wraps `@ResourceQualifier(type=Subscriber.class, config="<bare-topic>")`) returning
  `Promise<Unit>`. Subscriber methods need NO route. FQNs:
  `org.pragmatica.aether.slice.{Publisher,Subscriber}`, `org.pragmatica.aether.slice.annotation.ResourceQualifier`.
- **Resources:** `@Http org.pragmatica.aether.resource.http.HttpClient` (`postJson(url, body, Class<T>)`);
  `@Notify org.pragmatica.aether.resource.notification.NotificationSender` (`send(Notification)`).
- **Direct inter-slice call:** inject the callee interface as an unannotated factory param.
- **Facts** carry only String/long (parsed back into VOs on receipt) to keep wire codecs trivial.
- **Combinators:** `Result.all(...).map(...)`, `Result.async()`, `Option.async(Cause)`,
  `Promise.{map,flatMap,mapError,recover}`, `Promise.{success,failure,UNIT}`, `Option.{option,present,empty}`.
- Slice factory: `static Iface iface(@PgSql Store s, …) { record iface(…) implements Iface {…} return new iface(…); }`
  — factory name = lowercase-first interface name, distinct from every method name.
