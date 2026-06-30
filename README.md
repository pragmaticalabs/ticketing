# Ticketing Platform — a PFD → JBCT → Aether reference

A complete, runnable realization of the **event-ticketing example** threaded through the
*Process-First Design* book, implemented with **JBCT** (functional Java: `Result`/`Option`/`Promise`,
parse-don't-validate, sealed typed failures) on the **Aether** unified runtime. This is the
posterchild: the book designs the processes; this repo runs them.

> Built and tested on Pragmatica Lite / Aether / JBCT **1.0.0-rc2**, Java 25.
> Design rationale and the full process catalog live in [`docs/DESIGN.md`](docs/DESIGN.md).

---

## The telescope, as packages

PFD's four altitudes — **system → subsystem → workflow → use case** — are the package hierarchy.
Each **use case is one slice** (one `Request`/`Response`, one `execute(Request)` method), the leaf of
the tree. 23 single-use-case slices:

```
org.pragmatica.example.ticketing/                 (system)
  shared/ · shared/event/                          (value objects · facts + pub-sub qualifiers)
  booking/                                         (subsystem — BookingStore)
    purchase/      BuyTicket          cancellation/  CancelTicket
    hold/          AcquireHold · CheckHold · SweepHolds
  pricing/                                         (subsystem — PricingStore)
    schedule/      SetPrice · AdjustPrice          quoting/  QuotePrice
  eventmanagement/                                 (subsystem — EventStore)
    lifecycle/     CreateEvent · OpenEvent · CancelEvent
    capacity/      AddSeat · BlockSeat · ReleaseSeat
    sales/         SaleStatus
    convergence/   MarkSeatSold · MarkSeatReleased          ← SeatSold/SeatReleased consumers
  availability/                                    (read subsystem — per-use-case @PgSql interfaces)
    query/         SeatStatus · SoldCount
    projection/    ProjectSeatSold · ProjectSeatReleased    ← consumers
  quote/                                           (read subsystem — per-use-case @PgSql interfaces)
    query/         QuoteForCustomer                projection/  ProjectPrice   ← PriceChanged consumer
```

Every use-case slice is an interface with nested `Request`/`Response` records, a sealed `…Error
extends Cause`, and one method `Promise<Response> execute(Request)`. The write subsystems share one
`@PgSql` store each (`BookingStore`, `PricingStore`, `EventStore`); the read subsystems
(`availability`, `quote`) instead use **per-use-case `@PgSql` interfaces** co-located with each slice
(interface segregation — no slice there needs more than one method). The composition is the book's
**enterprise profile** vector: **synchronous reads across subsystems** (`BuyTicket` calls `QuotePrice`
and `SaleStatus` directly) and **typed facts over pub-sub** for cross-subsystem state
(`SeatSold`/`SeatReleased`/`PriceChanged`, with `convergence`/`projection` consumer slices).

**All six PFD patterns** appear — Leaf (VO factories/adapters), Sequencer (`BuyTicket` saga),
Fork-Join (`SaleStatus` ∥ eligibility in `BuyTicket`), Condition (cancellable?, hold decay),
Iteration (`SweepHolds`), Aspects (declared compensation). **All three recovery classes** — BER
(`BuyTicket`/`CancelTicket` money sagas), FER (`Fresh→Stale→Expired` holds, best-effort notify),
design-out (the seat claim, the price log).

### The design-out seat claim (the heart of it)

Two buyers cannot double-book: the reservation table has one row per seat, and the claim is a single
guarded statement that also reclaims expired holds inline —

```sql
INSERT INTO reservations (...) VALUES (..., 'held', now() + interval '15 minutes', now())
ON CONFLICT (seat_id) DO UPDATE SET ... state = 'held', ...
WHERE reservations.state IN ('cancelled','expired')
   OR (reservations.state = 'held' AND reservations.expires_at < now())
RETURNING id
```

A fresh hold or a confirmed booking can't be overwritten (the loser gets zero rows → typed
`SeatUnavailable`); a stale/expired hold is stolen atomically. No version column, no race — the
conflict is impossible by the shape of the statement.

---

## Build & test

> **Prerequisite:** requires Pragmatica Lite / Aether / JBCT **`1.0.0-rc2`** in your local Maven
> repository, including the `slice-processor` codegen fixes merged via
> [pragmaticalabs/pragmatica#364](https://github.com/pragmaticalabs/pragmatica/pull/364). Until the
> official `1.0.0-rc2` artifacts are published, build the processor from the `release-1.0.0-rc2`
> branch (`mvn -pl jbct/slice-processor install -DskipTests`).

```bash
mvn clean install         # compiles (slice-processor + pg-codegen), runs 125 unit tests, generates target/blueprint.toml (23 slices)
jbct check src/main/java  # JBCT format + lint (0 errors, 0 warnings)
```

Each use-case slice has unit tests (validation, happy path, each typed failure) against in-memory
fakes — no database required.

## Run locally (Forge)

```bash
./start-postgres.sh                 # PostgreSQL 17 (db=forge); Aether applies src/main/resources/schema on deploy
python3 scripts/stub-gateway.py &   # stub payment gateway on :9100 (BuyTicket's @Http target)
# optional: a MailHog/Mailpit SMTP sink on :1025 captures @Notify mail — notifications are FER, optional
./run-forge.sh                      # build + 5-node cluster; deploys the blueprint BY COORDINATE; app on :8070
```

> The rc1 forge resolves `--blueprint` as an **artifact coordinate** (not a file path); `run-forge.sh`
> passes `org.pragmatica.example:ticketing:1.0.0-SNAPSHOT:blueprint` accordingly.

### A walk through the API (`:8070`)

Routes mirror the telescope. Representative flow:

```bash
curl -s :8070/api/v1/events/create -d '{"venue":"O2 Arena","onSaleAt":"2026-07-01T10:00:00Z"}'   # -> event
curl -s :8070/api/v1/seats/add     -d '{"event":"<e>","section":"A","row":"12","number":7,"tier":"STANDARD"}'  # -> seat
curl -s :8070/api/v1/pricing/set   -d '{"event":"<e>","tier":"STANDARD","amount":"49.50","currency":"USD"}'
curl -s -X POST :8070/api/v1/events/open/<e>
curl -s :8070/api/v1/booking/buy   -d '{"customer":"<uuid>","event":"<e>","seat":"<s>","tier":"STANDARD"}'
curl -s :8070/api/v1/pricing/quote/<e>/STANDARD          # authoritative quote
curl -s :8070/api/v1/availability/seats/<s>              # 'sold' after SeatSold propagates
curl -s :8070/api/v1/booking/cancel -d '{"booking":"<b>","customer":"<uuid>"}'
```

Each slice's exact route + error→status map: `src/main/resources/.../<usecase>/routes.toml`.

---

## What "test our own tool" found

Building the book's idiomatic patterns — and then the one-use-case-per-slice + synchronous-call design
— surfaced real toolchain issues (full list: [`docs/DESIGN.md`](docs/DESIGN.md) §8):

- **Two slice-processor codegen bugs, both fixed in the runtime** — on the rc2 branch via PR
  [pragmaticalabs/pragmatica#364](https://github.com/pragmaticalabs/pragmatica/pull/364), each with a
  regression test (the project builds green on **1.0.0-rc2**):
  1. duplicate single-type imports when two error types share a simple name (the book's per-VO
     `Blank`/`Malformed` guarantee it);
  2. generated codecs referenced an injected slice's nested `Request`/`Response` by *simple* name,
     which the host slice's inherited member types shadow (JLS §6.5.5.2) — guaranteed by "every slice
     has `Request`/`Response`" + "a slice injects another slice". Both now emit fully-qualified names;
     both have regression tests.
- **pg-codegen / structure workarounds, documented:** `schema/migrations.list` manifest; single-line
  `@Query` (no text blocks, no data-modifying CTEs); one parameter per method; `@Notify` in a separate
  `resource-notification` artifact; slice-factory transitive deps cap at 15 (the split keeps every
  factory well under); no `@Heartbeat`; topics must be flat kebab-case; the forge `--blueprint`
  coordinate form. These are the friction a real telescope-shaped app finds that a HelloWorld never will.

A consolidated, prioritized DX wish list for the Aether toolchain — distilled from building this app —
lives in [`docs/AETHER-WISHLIST.md`](docs/AETHER-WISHLIST.md).

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE). See [`NOTICE`](NOTICE) for attribution.
