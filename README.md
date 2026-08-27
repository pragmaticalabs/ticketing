# Ticketing Platform — a PFD → JBCT → Aether reference

A complete, runnable realization of the **event-ticketing example** threaded through the
[*Process-First Design*](https://leanpub.com/process-first-design) book, implemented with **[JBCT](https://pragmatica.dev/)** (functional Java: `Result`/`Option`/`Promise`,
parse-don't-validate, sealed typed failures; see the [JBCT book](https://leanpub.com/jbct-book)) on the **[Aether](https://github.com/pragmaticalabs/pragmatica/tree/main/aether)** unified runtime. This is the
posterchild: the book designs the processes; this repo runs them.

> **⚠️ Status — `1.0.0-rc3` is a local build; none of it is on Maven Central.** This project builds
> and passes its full test suite on **Pragmatica Lite / Aether / JBCT `1.0.0-rc3`** (Java 25). **rc3
> is not published anywhere** — every rc3 artifact in `~/.m2` came from a local `mvn install` of the
> `release-1.0.0-rc3` branch of the pragmatica repo. Central's newest published line is still rc2.
> Three of this project's dependencies have never been published on *any* rc line, so they stay
> local-install-only even after rc3 ships: **`resource-api`, `resource-notification`, and
> `resource-interceptors`** — the `org.pragmatica.aether.resource.*` API (`@PgSql`, `@Http`,
> `@Notify`) plus the interceptor factories. A fresh clone must build the rc3 branch locally first.
>
> **This is tracked as [pragmaticalabs/pragmatica#668](https://github.com/pragmaticalabs/pragmatica/issues/668)**
> — "GA gate: publish aether artifacts to Maven Central." Until it lands, **every fresh clone of this
> repo must build the `pragmatica` monorepo from source into `~/.m2` before `mvn install` here can
> resolve anything under `org.pragmatica-lite:*`** (see Step 0 in "Run locally (Forge)" below). Once
> #668 ships, that local build step goes away for whatever artifact set it covers — `mvn install` in
> this repo starts resolving those coordinates from Central instead, the same way `core` and
> `jbct-maven-plugin` already do at rc2. Watch the issue rather than this README for the exact
> artifact list and version it lands at.
>
> Design rationale and the full process catalog live in [`docs/DESIGN.md`](docs/DESIGN.md).

---

## The telescope, as packages

PFD's four altitudes — **system → subsystem → workflow → use case** — are the package hierarchy.
Each **use case is one slice** (one `Request`/`Response`, one `execute(Request)` method), the leaf of
the tree. 24 single-use-case slices:

| Subsystem (persistence) | Workflow | Use-case slices |
|---|---|---|
| **booking** — `BookingStore` | `purchase/` | `BuyTicket` |
| | `cancellation/` | `CancelTicket` |
| | `hold/` | `AcquireHold`, `CheckHold`, `SweepHolds` |
| **pricing** — `PricingStore` | `schedule/` | `SetPrice`, `AdjustPrice` |
| | `quoting/` | `QuotePrice` |
| **eventmanagement** — `EventStore` | `lifecycle/` | `CreateEvent`, `OpenEvent`, `CancelEvent` |
| | `capacity/` | `AddSeat`, `BlockSeat`, `ReleaseSeat`, `SeatSellability` |
| | `sales/` | `SaleStatus` |
| | `convergence/` | `MarkSeatSold`, `MarkSeatReleased` *(SeatSold/SeatReleased consumers)* |
| **availability** — per-use-case `@PgSql` | `query/` | `SeatStatus`, `SoldCount` |
| | `projection/` | `ProjectSeatSold`, `ProjectSeatReleased` *(consumers)* |
| **quote** — per-use-case `@PgSql` | `query/` | `QuoteForCustomer` |
| | `projection/` | `ProjectPrice` *(PriceChanged consumer)* |

19 of the 24 are HTTP-routed; the 5 fact consumers (`convergence/`, `projection/`) carry no route.

Above the subsystems, `shared/` holds the cross-cutting value objects (`Money`, `Percent`,
`SeatState`, the id types, `SeatLocation`) and `shared/event/` the pub-sub facts (`SeatSold`,
`SeatReleased`, `PriceChanged`).

Every use-case slice is an interface with nested `Request`/`Response` records, a sealed `…Error
extends Cause`, and one method `Promise<Response> execute(Request)`. The write subsystems share one
`@PgSql` store each (`BookingStore`, `PricingStore`, `EventStore`); the read subsystems
(`availability`, `quote`) instead use **per-use-case `@PgSql` interfaces** co-located with each slice
(interface segregation — no slice there needs more than one method). The composition is the book's
**enterprise profile** vector: **synchronous reads across subsystems** (`BuyTicket` calls `QuotePrice`
and `SaleStatus` directly) and **typed facts over pub-sub** for cross-subsystem state
(`SeatSold`/`SeatReleased`/`PriceChanged`, with `convergence`/`projection` consumer slices).

**All six PFD patterns** appear — Leaf (VO factories/adapters), Sequencer (`BuyTicket` saga),
Fork-Join (`SaleStatus` ∥ eligibility ∥ sellability in `BuyTicket`), Condition (cancellable?, hold
decay), Iteration (`SweepHolds`), Aspects (declared compensation, plus the cache/logging
interceptors attached at the slice boundary). **All three recovery classes** — BER
(`BuyTicket`/`CancelTicket` money sagas), FER (`Fresh→Stale→Expired` holds, best-effort notify),
design-out (the seat claim, the price log).

Booking never reads the `seats` table directly — it is owned by event-management. Instead
`AcquireHold` and `BuyTicket` inject the **`SeatSellability`** slice, which resolves the
authoritative seat state and answers one question: may this seat be sold? `BLOCKED` and `WITHDRAWN`
seats answer no, so blocking a seat now actually prevents holds and purchases.

### The design-out seat claim (the heart of it)

Two buyers cannot double-book: `reservations` has **`seat_id` as its primary key** — one row per seat,
forever — and the claim is a single guarded statement that also reclaims expired holds inline. The row
carries a non-key **`claim_id`** that the database rotates on every successful claim, plus a per-seat
`version` that increments with it:

```sql
INSERT INTO reservations (seat_id, claim_id, ..., state, expires_at, held_since, version)
VALUES (:seatId, gen_random_uuid(), ..., 'held', now() + interval '15 minutes', now(), 0)
ON CONFLICT (seat_id) DO UPDATE SET claim_id = gen_random_uuid(), state = 'held',
  held_since = CASE WHEN <same customer, still live> THEN reservations.held_since ELSE now() END,
  version = reservations.version + 1
WHERE reservations.state IN ('cancelled','expired')
   OR (reservations.state = 'held' AND reservations.expires_at < now())
   OR (reservations.state = 'held' AND reservations.customer_id = EXCLUDED.customer_id
       AND reservations.held_since > now() - interval '60 minutes')
RETURNING claim_id, version
```

A confirmed booking or another customer's fresh hold can't be overwritten (the loser gets zero rows →
`StateConflict.SEAT_UNAVAILABLE`, a 409); a stale/expired hold is stolen atomically. The third branch lets the *owner*
refresh their own hold, but only within a 60-minute total lifetime measured from `held_since`, which
that branch alone preserves — so a client re-acquiring on a timer can no longer squat a seat forever.
No optimistic-retry loop, no application-level lock: the conflict is impossible by the shape of the
statement.

`claim_id` is what the rest of the saga is guarded by — `confirmReservation`/`releaseReservation`
match on it, so compensation can never release a claim that has since rotated away from it. Making
the claim a *point-in-time fact* rather than an identity is what unblocked two previously-permanent
failures: **reselling a cancelled seat** (the old `bookings → reservations` foreign key made the row
un-reusable) and **converting a hold into a purchase** (which had always failed).

---

## Build & test

> **Prerequisite (see the Status note above):** the `1.0.0-rc3` line is **not on Maven Central at
> all**. Build and `mvn install` the `release-1.0.0-rc3` branch of the pragmatica repo first; the
> three `resource-*` artifacts (`resource-api`, `resource-notification`, `resource-interceptors`)
> have never been published on any rc line and can only come from that local build
> (`mvn install` under `aether/resource`).

```bash
mvn clean install         # compiles (slice-processor + pg-codegen), runs 214 unit tests, generates target/blueprint.toml (24 slices)
jbct check src/main/java  # JBCT format + lint (0 errors, 0 warnings)
jbct format src/test/java # test sources are NOT auto-formatted by the build -- see below
```

The Maven `jbct` plugin binds its `format` goal to `process-sources`, which only covers
`src/main/java`. **Test sources are never reformatted by a build** — run `jbct format src/test/java`
explicitly, or drift accumulates there silently. The CLI at `~/.jbct` and the Maven plugin are the
same `1.0.0-rc3` engine and produce byte-identical output, so it does not matter which one you run.

Each use-case slice has unit tests (validation, happy path, each typed failure) against in-memory
fakes — no database required.

## Access control and error status

Every one of the 19 routed slices declares a `[security]` block: **8 `role:admin`** (the
event-management and pricing write surface), **4 `authenticated`** (the customer booking
operations), **1 `role:operator`** (the hold sweep), and **6 `public`** (the read/query slices and
the seat-sellability lookup). There is no implicit default — an unlabelled route is a build-time
omission, not a silently public endpoint.

Every `[errors]` block sets `strict = true`, so an unmapped `Cause` fails the build rather than
falling through to a 500 at runtime. Malformed input maps to **400** (18 slices) or **422** (6
slices, for well-formed-but-unacceptable values). Because the generated router only matches causes
declared in the slice's *own* package, each slice **restates** shared value-object failures as its
own typed `InvalidRequest`/`UnacceptableValue`, and `shared/Validation.firstFailure` unwraps the
`CompositeCause` that `Result.all` produces — without both, every validation failure would reach the
client as a 500.


## Run locally (Forge)

Aether has four runnable/embeddable surfaces: **Forge** (a dashboarded dev cluster with load/failure
tooling — the one this section uses), the production **`Main`** node, the **`aether` CLI**, and
**Ember**, which is embeddable-only and has no binary of its own (`Ember.cluster(n).withH2()...
start()` inside a JVM you write). For a from-zero developer, Forge is the entry point.

This walkthrough assumes **nothing installed** beyond a JDK, Maven, and a container runtime — every
command below was actually run against this repository on 2026-08-27; where output is quoted, it is
the real output observed, not a mock-up.

### Prerequisites (verified against this repo's build files)

| Tool | Required | Verified with | Source of truth |
|---|---|---|---|
| JDK | 25+ | Homebrew OpenJDK 25.0.2 | `pom.xml` → `maven.compiler.release=25` |
| Maven | 3.9+ (no enforced floor in this pom; use a recent 3.9.x) | 3.9.12 | practical — untested below this |
| Docker or Podman | any recent version | Docker 29.3.0 | `start-postgres.sh` auto-detects either |
| git | any | — | to clone `pragmatica` in Step 0 |

### Step 0 — build the Aether runtime from source

**Required today** because of [#668](https://github.com/pragmaticalabs/pragmatica/issues/668) (see the
Status note above): nothing this project needs under `org.pragmatica-lite:*` is on Maven Central at
rc3, so `~/.m2` has to be populated by building the monorepo yourself.

```bash
git clone https://github.com/pragmaticalabs/pragmatica.git ~/IdeaProjects/pragmatica
cd ~/IdeaProjects/pragmatica
git checkout release-1.0.0-rc3

# bootstrap the annotation processors / Maven plugins the rest of the build needs, then install everything
mvn install -DskipTests -Djbct.skip=true -pl jbct/jbct-maven-plugin,jbct/slice-processor,aether/pg-tools/pg-codegen -am
mvn install -DskipTests
```

These are steps 1 and 3 of the monorepo's own `./build.sh` (6 steps total) — the only two an external
consumer needs; the rest are the project's own lint gate and test-blueprint builds.

> **Do not run `mvn verify` anywhere in the pragmatica repo.** Its Failsafe integration suite includes
> a Hetzner Cloud test that binds to the real Hetzner API and provisions a paid server if
> `HCLOUD_TOKEN` is set in your environment. `mvn install` (used above) never touches it.

**On timing:** this was verified to *work* (a clean `mvn install -DskipTests` against a warm `~/.m2`
completed in well under a minute), but that number is **not** a true cold-cache figure — this
environment's local repository and `target/` directories were already populated from prior builds. A
genuine from-zero build (empty `~/.m2`, no `target/`) compiles the full `core` + `aether` + `jbct`
tree and will take meaningfully longer; budget several minutes on first run rather than trusting the
warm-cache number.

**How to know it worked:**

```bash
ls ~/.m2/repository/org/pragmatica-lite/aether/resource-api/
ls ~/.m2/repository/org/pragmatica-lite/aether/forge-core/
```

Both should list a `1.0.0-rc3` directory. If `forge-core` is missing, note it lives at
`aether/forge-core/` in the monorepo, **not** `aether/forge/forge-core/` — a reasonable-looking but
wrong path if you're hunting for it manually.

Back in this repo, confirm resolution actually works:

```bash
mvn clean install -DskipTests -q   # should succeed silently; this is what run-forge.sh does for you
```

### Run the cluster

```bash
./start-postgres.sh                 # PostgreSQL 17 (db=forge); Aether applies src/main/resources/schema on deploy
python3 scripts/stub-gateway.py &   # stub payment gateway on :9100 (BuyTicket's @Http target)
# optional: a MailHog/Mailpit SMTP sink on :1025 captures @Notify mail — notifications are FER, optional
./run-forge.sh                      # rebuilds the slice + 5-node cluster; deploys the blueprint BY COORDINATE; app on :8070
```

`./start-postgres.sh` prints its own confirmation; on an already-running container this repo actually
produced:

```
PostgreSQL is already running (container: ticketing-postgres)
Applying schema/init.sql...

PostgreSQL running on port 5432
  Connection: postgresql://postgres:postgres@localhost:5432/forge
```

On a first run it instead prints `Creating PostgreSQL container...` / `Waiting for PostgreSQL...`
before the same final block.

> **`aether.toml`'s `[database]` block must be filled in — it is not optional despite the file's own
> "uncomment to enable" comment.** Every `@PgSql`-backed slice (most of them — see the telescope table
> above) needs `database.async_url` to provision its store; leave the block commented out (the
> checked-out default) and those slices fail to deploy. This checkout already has it set to match
> `start-postgres.sh`'s connection string:
> ```toml
> [database]
> async_url = "postgresql://postgres:postgres@localhost:5432/forge"
> ```
> Don't confuse this with `forge.toml`'s own `[database] enabled = false` — that is a **separate,
> unrelated flag** for Ember's embedded H2 simulator database, not Postgres. Leave it `false` when
> running against real Postgres as documented here.

`./run-forge.sh` rebuilds (`mvn clean install -DskipTests -q`) and then launches Forge. The forge
resolves `--blueprint` as an **artifact coordinate** (not a file path); the script passes
`org.pragmatica.example:ticketing:1.0.0-SNAPSHOT:blueprint` accordingly. Its own printed "Test:" hint
(`curl ... /api/v1/events -d ...`) uses a stale path missing `/create` — use the walkthrough below
instead.

**How to know it worked** — real log lines from this session, in order:

```
INFO  ForgeServer.handleDeployResponse() - Blueprint deployed from artifact: org.pragmatica.example:ticketing:1.0.0-SNAPSHOT:blueprint
INFO  ClusterTopologyManager.activateWithCurrentTopology() - CTM: Activated, desired=5, active=5, ready=5
INFO  ForgeServer.lambda$launchHttpServer$0() - HTTP server started on port 8888
INFO  ForgeServer.start() - Forge server running. Press Ctrl+C to stop.
```

```bash
curl -s -o /dev/null -w "%{http_code}\n" localhost:8888   # -> 200 (dashboard)
```

Then open the dashboard at **http://localhost:8888** to watch slice deployment and cluster health.

### ⚠️ Known issue in this environment — HTTP routes never come up (verified, unresolved)

**This is a finding, not a documentation gap.** Following every step above exactly — database
configured, Postgres reachable, `CTM: Activated, desired=5, active=5, ready=5` logged, dashboard
returning 200 — the API on `:8070` still returns 404 for every documented route, reproduced
independently **three times** in this session with a full rebuild each time:

```
$ curl -s -X POST localhost:8070/api/v1/events/create -d '{"venue":"O2 Arena","onSaleAt":"2026-07-01T10:00:00Z"}'
{"type":"about:blank","title":"Not Found","status":404,"detail":"No route found for POST /api/v1/events/create","instance":"/api/v1/events/create","requestId":"req-01m12gks2begasdx4c2harsmyz"}
```

`GET :8888/api/slices/status` (the dashboard's own status API) shows why: every slice instance across
all 5 nodes stays `UNHEALTHY` and none ever reaches the `ACTIVE` state that the router requires before
it wires up a route — including `create-event`, which has no error anywhere in its deployment log.
Several slices kept cycling `LOADING → LOADED → UNLOADING` rather than settling, even after 30+
seconds of otherwise-idle wait. **`[verified symptom]`** — reproduced 3× via the exact steps above.
**`[design intent — unverified root cause]`** — the logs also show sustained Rabia consensus
backpressure during this window (`SLOW-APPLY` warnings, `Backpressure on peer ... lane CONSENSUS`,
and once `Consensus apply timed out after 30000ms` while reconciling a slice), consistent with the
5-node embedded consensus being saturated by concurrently deploying 24 slices × 3 replicas on one
machine — but this has not been confirmed as the actual cause, only observed alongside it.

**Separately (fixed by rebuilding, not a standing issue):** if you ever invoke the `aether-forge` jar
directly against stale `~/.m2` artifacts — bypassing `run-forge.sh`'s build step — you will see
`ClassNotFoundException: SeatSellability` / `PriceChanged` on several slices instead. That is a stale-
artifact symptom, not this bug; a plain `mvn clean install` (which `run-forge.sh` already does for
you) resolves it.

If you hit this, you have reproduced the same state we did — it is not something wrong with your
setup. There is no known workaround yet.

### A walk through the API (`:8070`)

Routes mirror the telescope. This is each route's **designed** contract — the path, method, and
response shape from `routes.toml` and the slice's own `Response` record, exercised by this project's
214 unit tests against in-memory fakes (see "Build & test" above) — **not** verified end-to-end
through Forge in this environment, per the known issue directly above:

```bash
curl -s :8070/api/v1/events/create -d '{"venue":"O2 Arena","onSaleAt":"2026-07-01T10:00:00Z"}'   # -> {"event":"<id>"}
curl -s :8070/api/v1/seats/add     -d '{"event":"<e>","section":"A","row":"12","number":7,"tier":"STANDARD"}'  # -> seat
curl -s :8070/api/v1/pricing/set   -d '{"event":"<e>","tier":"STANDARD","amount":"49.50","currency":"USD"}'
curl -s -X POST :8070/api/v1/events/open/<e>
curl -s :8070/api/v1/booking/buy   -d '{"customer":"<uuid>","event":"<e>","seat":"<s>","tier":"STANDARD"}'
curl -s :8070/api/v1/pricing/quote/<e>/STANDARD          # authoritative quote
curl -s :8070/api/v1/availability/seats/<s>              # 'sold' after SeatSold propagates
curl -s :8070/api/v1/booking/cancel -d '{"booking":"<b>","customer":"<uuid>"}'
```

Each slice's exact route + error→status map: `src/main/resources/.../<usecase>/routes.toml`.

### Troubleshooting

- **`ERROR: aether-forge not found`** from `run-forge.sh` — Step 0 wasn't completed, or the
  `aether-forge` launcher isn't on `PATH` and isn't at `~/.aether/bin/aether-forge` either. Re-run
  Step 0's `mvn install` commands from the pragmatica repo.
- **`ERROR: Neither docker nor podman found`** from `start-postgres.sh` — install one, or start Docker
  Desktop if it's installed but not running (`docker ps` failing silently is the usual tell).
- **Port already in use** (`5432`, `8070`–`8074`, `5150`, `8888`, or `8080`) — a previous
  `./start-postgres.sh` container or `./run-forge.sh` process is still up. Check with
  `docker ps` / `ps aux | grep aether-forge`, and reuse it rather than starting a second one — Forge
  doesn't multiplex cleanly on a laptop with two clusters fighting over the same ports.
  `PG_PORT=<other>` overrides Postgres's port if you need to run it side-by-side with something else.
- **Slices fail to deploy with `ClassNotFoundException`** referencing a class that clearly exists in
  `src/main/java` — stale `~/.m2` artifacts from a previous build of a different working-tree state.
  `mvn clean install -DskipTests` (or just re-run `./run-forge.sh`, which does this first) fixes it.
- **Every route 404s despite a clean deploy** — see the "Known issue" callout above before assuming
  your setup is wrong; as of this writing it reproduces on a fully correct setup too.
- **`aether.toml`'s `[database]` commented out** — every `@PgSql` slice fails to provision its store.
  See the "Run the cluster" section above; this is required, not optional, contrary to the file's own
  comment.

### Not covered here

- **Production deployment** (the `Main` node, cloud provisioning, Hetzner/other cloud targets) —
  outside this repo's scope; see the pragmatica monorepo's `aether/docs/` for that.
- **The `aether` CLI** (`~/.aether/bin/aether`) — used for operating a running cluster (schema
  migrations, deployment control); not needed to just run this project locally.
- **Embedding Ember directly** (`Ember.cluster(n).withH2()...start()` in your own JVM, no dashboard) —
  Forge is Ember plus a dashboard and load/failure tooling; see the pragmatica monorepo's `aether/`
  module docs if you want the bare library instead.

---

## What "test our own tool" found

Building the book's idiomatic patterns — and then the one-use-case-per-slice + synchronous-call design
— surfaced real toolchain issues (full list: [`docs/DESIGN.md`](docs/DESIGN.md) §8):

- **Three slice-processor codegen bugs found here — all three fixed upstream.** The first two went in
  via PR [pragmaticalabs/pragmatica#364](https://github.com/pragmaticalabs/pragmatica/pull/364), each
  with a regression test, and shipped in `slice-processor:1.0.0-rc2`:
  1. duplicate single-type imports when two error types share a simple name (the book's per-VO
     `Blank`/`Malformed` guarantee it);
  2. generated codecs referenced an injected slice's nested `Request`/`Response` by *simple* name,
     which the host slice's inherited member types shadow (JLS §6.5.5.2) — guaranteed by "every slice
     has `Request`/`Response`" + "a slice injects another slice". Both now emit fully-qualified names.

  The third surfaced on rc3, on the first interceptor added: an interceptor
  `@ResourceQualifier(config = "cache.availability.seat-status")` derived its generated lambda
  parameter name with `configSection.replace('.', '_')` as the only sanitization, so a **hyphen**
  survived into an illegal Java identifier and javac failed *inside generated code*, with no
  diagnostic pointing at the slice. Fixed upstream in commit `311a1b0d7` (#561) on
  `release-1.0.0-rc3`: `ResourceQualifierModel.variableSafeConfigSection()` now maps every code point
  failing `Character.isJavaIdentifierPart` to `_`, and because that mapping is not injective (`a-b`
  and `a_b` collapse onto one name) the generator additionally de-collides issued names with a
  numeric suffix — three regression tests cover the hyphen, the separator collision, and the plain
  dotted form. **The workaround here is removed:** all eight interceptor sections are hyphenated
  again (`[cache.availability.seat-status]`, `[log.quote.project-price]`), so `resources.toml` is
  once more consistent with its topic and scheduling sections. Only the local variable name is
  sanitized — the `provide(Type.class, "…")` literal still carries the section verbatim, so
  resolution is unchanged.
- **Fixed since rc1, verified here:** migrations are auto-discovered from `V*__*.sql` (the
  `schema/migrations.list` manifest this project used to carry is deleted); text-block `@Query`
  emits correctly and the stores use it throughout; `[errors] strict = true` turns an unmapped
  `Cause` into a build failure.
- **Still true, still shaping the design:** one parameter per slice method (the JBCT idiom anyway);
  no data-modifying CTEs in `@Query` — now a clear compile error rather than silent mis-validation;
  `@Notify` and the interceptor factories live in separate, unpublished artifacts; the forge
  `--blueprint` coordinate form. **`Promise.all` still caps at 15** in core — the slice-processor's
  `BatchedAll` batches a *generated* factory's transitive dependencies beyond that, but the cap is
  unchanged for code you write by hand.
- **Interceptors are adoptable only at the slice boundary.** 3 `LOCAL` caches on the read slices and
  5 logging interceptors on the fact consumers are wired. Payment retry / circuit-breaking is **not**
  adoptable — see [`docs/AETHER-WISHLIST.md`](docs/AETHER-WISHLIST.md) for why it would open the
  breaker on a hot event.

These are the friction a real telescope-shaped app finds that a HelloWorld never will.

A consolidated, prioritized DX wish list for the Aether toolchain — distilled from building this app —
lives in [`docs/AETHER-WISHLIST.md`](docs/AETHER-WISHLIST.md).

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE). See [`NOTICE`](NOTICE) for attribution.
