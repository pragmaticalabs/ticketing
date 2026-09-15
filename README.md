# Ticketing Platform — a PFD → JBCT → Aether reference

A complete, runnable realization of the **event-ticketing example** threaded through the
[*Process-First Design*](https://leanpub.com/process-first-design) book, implemented with **[JBCT](https://pragmatica.dev/)** (functional Java: `Result`/`Option`/`Promise`,
parse-don't-validate, sealed typed failures; see the [JBCT book](https://leanpub.com/jbct-book)) on the **[Aether](https://github.com/pragmaticalabs/pragmatica/tree/main/aether)** unified runtime. This is the
posterchild: the book designs the processes; this repo runs them.

Every dependency resolves from **Maven Central at `1.0.0-rc3`** — there is no source build of
Aether. `git clone` + `mvn install` is the whole story.

Start with **Quick start** below. Design rationale and the full process catalog live in
[`docs/DESIGN.md`](docs/DESIGN.md); the shape of the code is summarized under
[The design](#the-design).

---

## Quick start

### Prerequisites

| Tool | Required | Source of truth |
|---|---|---|
| JDK | 25+ | `pom.xml` → `maven.compiler.release=25` |
| Maven | 3.9+ (use a recent 3.9.x) | practical — untested below this |
| Docker or Podman | any recent version | `start-postgres.sh` auto-detects either |
| git | any | to clone this repository |

A JDK is required even though the Aether binaries ship their own JRE — the **`jbct` CLI runs as
`java -jar`** and will refuse to install without one.

### 1. Install the toolchain

Two command-line tools come from GitHub releases rather than Maven Central: the **`jbct` CLI**
(format/lint, project scaffolding) and the **Aether binaries** (`aether`, `aether-node`,
`aether-forge`). One installer does both:

```bash
curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/install.sh \
  | sh -s -- --version 1.0.0-rc3
```

> Piped installs cannot take arguments directly — the `sh -s --` is what passes `--version` through.

What it puts where:

| Tool | Installed to | Notes |
|---|---|---|
| `jbct` | `~/.jbct/bin/jbct` (+ `~/.jbct/lib/jbct.jar`) | a `java -jar` wrapper — **needs the JDK above** |
| `aether`, `aether-node`, `aether-forge` | `~/.aether/bin/` | self-contained archives with a bundled JRE |

Both installers append their `bin` directory to `PATH` in your shell rc file (`~/.zshrc`,
`~/.bashrc` or `~/.bash_profile`) when it is not already there, so **open a new shell** — or source
that file — before the next step. `run-forge.sh` also looks in `~/.aether/bin` directly, so Forge
works even if `PATH` was not updated.

Confirm both are present:

```bash
jbct --version          # -> 1.0.0-rc3 (built ...)
aether-forge --help     # or: ls ~/.aether/bin
```

JBCT and Aether are versioned independently. `--version` above sets both; use `--jbct-version` /
`--aether-version` when they need to differ.

### 2. Build and test

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

### 3. Start the infrastructure

```bash
./start-postgres.sh                 # PostgreSQL 17 (db=forge); Aether applies src/main/resources/schema on deploy
./start-gateway.sh                  # stub payment gateway on :9100 (BuyTicket's @Http target), WireMock + gateway-stubs/
#   ... or, with no WireMock available:  python3 scripts/stub-gateway.py &
# optional: a MailHog/Mailpit SMTP sink on :1025 captures @Notify mail — notifications are FER, optional
```

`./start-postgres.sh` prints its own confirmation:

```
PostgreSQL running on port 5432
  Connection: postgresql://postgres:postgres@localhost:5432/forge
```

> **`aether.toml`'s `[database]` block is enabled by default.** Every `@PgSql`-backed slice needs
> `database.async_url` to provision its store; a slice whose store cannot provision never leaves
> `LOADED`, so if this block is missing or commented out those slices fail to deploy. The
> checked-out default already matches `start-postgres.sh`'s connection string:
> ```toml
> [database]
> async_url = "postgresql://postgres:postgres@localhost:5432/forge"
> ```
> Don't confuse this with `forge.toml`'s own `[database] enabled = false` — that is a **separate,
> unrelated flag** for Ember's embedded H2 simulator database, not Postgres. Leave it `false` when
> running against real Postgres as documented here.

### 4. Run the cluster

```bash
rm -rf ~/.aether/forge-data         # REQUIRED before any re-run -- see "Re-running Forge" below
./run-forge.sh                      # rebuilds the slice + 5-node cluster; deploys the blueprint BY COORDINATE; app on :8070
```

Aether has four runnable/embeddable surfaces: **Forge** (a dashboarded dev cluster with load/failure
tooling — the one this section uses), the production **`Main`** node, the **`aether` CLI**, and
**Ember**, which is embeddable-only and has no binary of its own (`Ember.cluster(n).withH2()...
start()` inside a JVM you write). For a from-zero developer, Forge is the entry point.

`./run-forge.sh` rebuilds (`mvn clean install -DskipTests -q`) and then launches Forge. The forge
resolves `--blueprint` as an **artifact coordinate** (not a file path); the script passes
`org.pragmatica.example:ticketing:1.0.0-SNAPSHOT:blueprint` accordingly. Its own printed "Test:" hint
(`curl ... /api/v1/events -d ...`) uses a stale path missing `/create` — use the walkthrough below
instead.

**How to know it worked** — the log lines to look for, in order:

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

### 5. Enable authentication — MANDATORY, and shipped disabled

**`aether.toml`'s `[app-http]` block is required for the walkthrough, and it ships commented out.**
You must enable it before anything below the six `public` read slices will answer. 13 of the 19
routed slices declare `authenticated`, `role:admin` or `role:operator`, and until you enable the
block every one of them returns:

```json
{"status":401,"detail":"Route requires authentication but no security mode is configured"}
```

The walkthrough starts with `POST /api/v1/events/create`, which is `role:admin` — so it **fails on
its first step** until you do this. That is the shipped default behaving as intended, not a fault in
your setup.

**To enable it: open [`aether.toml`](aether.toml) and follow the instructions in the `[app-http]`
block** — delete the leading `# ` from each line between its two `ENABLE` markers. Then pass the key
on every non-public call:

```bash
curl -H "X-API-Key: local-dev-insecure-do-not-use" ...
```

That block is the authoritative description of the setting: what each field means, why a non-empty
key map specifically is what activates it, and how to supply real credentials through
`AETHER_API_KEYS` instead of editing the file. **It is deliberately not restated here** — a config
snippet duplicated into prose drifts from the file, and the drifted copy is the one a reader
follows.

`docker/verify-from-scratch.sh` performs this same enabling step, so the instruction above is
exercised on every verification run rather than merely asserted.

### 6. A walk through the API (`:8070`)

Routes mirror the telescope. Every call below was run end to end through Forge in a bare
`ubuntu:24.04` container and returned the status shown; this is observed output, not a designed
contract. Non-public routes need the API key from step 5.

```bash
K='X-API-Key: local-dev-insecure-do-not-use'

curl -s -H "$K" :8070/api/v1/events/create -d '{"venue":"O2 Arena","onSaleAt":"2026-07-01T10:00:00Z"}'   # -> {"event":"<id>"}
curl -s -H "$K" :8070/api/v1/seats/add     -d '{"event":"<e>","section":"A","row":"12","number":7,"tier":"STANDARD"}'  # -> {"seat":"<id>"}
curl -s -H "$K" :8070/api/v1/pricing/set   -d '{"event":"<e>","tier":"STANDARD","amount":"49.50","currency":"USD"}'    # -> {"version":1}

# NOTE the '{}' body. A path-parameter-only POST returns 500 "Type mismatch: expected Request, got
# unknown" when sent with no body at all -- an empty JSON object is what makes it bind the path var.
# Same for /api/v1/events/cancel/<e>.
curl -s -H "$K" -X POST :8070/api/v1/events/open/<e> -d '{}'                                            # -> {"event":"<e>"}

curl -s -H "$K" :8070/api/v1/booking/buy   -d '{"customer":"<uuid>","event":"<e>","seat":"<s>","tier":"STANDARD"}'
#   -> {"booking":"<b>","ticket":"<t>","seat":"<s>","receipt":"<r>","amountMinor":4950,"currency":"USD"}
curl -s :8070/api/v1/pricing/quote/<e>/STANDARD          # public -> {"amountMinor":4950,"currency":"USD","version":1}
curl -s :8070/api/v1/availability/seats/<s>              # public -> {"state":"available"}
                                                        #   NB: stays 'available' after a sale -- see the
                                                        #   fact-delivery known issue below, not a doc error
curl -s -H "$K" :8070/api/v1/booking/cancel -d '{"booking":"<b>","customer":"<uuid>"}'   # -> {"booking":"<b>","receipt":"<r>"}
```

Each slice's exact route + error→status map: `src/main/resources/.../<usecase>/routes.toml`.

---

## Operating notes

### ⚠️ Re-running Forge — delete `forge-data` first, or the cluster wedges

**This is the single most important operational fact in this document.**

Forge persists cluster state under `~/.aether/forge-data`. Starting Forge again over the state of a
previous run leaves the deployment reconciler working from instance counts that no longer describe
reality — it observes, in one tick, artifacts both above and below their desired replica count:

```
RECONCILIATION_SCALE_DOWN artifact=...acquire-hold currentInstances=4 desiredInstances=3
RECONCILIATION_SCALE_UP   artifact=...check-hold   currentInstances=2 desiredInstances=3
```

The corrections it issues then race each other. `UNLOAD` commands time out
(`Consensus apply timed out after 30000ms`), an `UNLOAD` removes a slice while its activation is
still in flight (`state is ACTIVATE but not found in SliceStore`), and that race is classified
terminally — `Deterministic failure ... — will NOT retry`. Nothing retries it, so the cluster does
not recover. It **wedges**: measured here, 11 consecutive samples over 110s reported a byte-identical
`ACTIVE=70, LOADING=6, UNLOADING=3, ACTIVATE=1` while six of the nineteen routes stayed 404.

So:

```bash
rm -rf ~/.aether/forge-data     # before every ./run-forge.sh
```

With `forge-data` removed and nothing else changed, the same 5-node config converges and the whole
walkthrough passes.

**A route can be 404 while its slice is `ACTIVE`.** `ticketing-buy-ticket` was observed with all
three instances `ACTIVE` and `POST /api/v1/booking/buy` returning 404 — route publication is not
repaired once lost. Do not use slice state to conclude anything about route availability, in either
direction.

### ⚠️ Known issue — cross-slice facts are not delivered (subscription cause identified; scheduling cause still unknown)

**The pub-sub fact path does not work in this deployment.** After a **successful** purchase
(`POST /api/v1/booking/buy` → 200 with a booking, ticket and receipt), the availability read model
never converges: `GET /api/v1/availability/seats/<seat>` still answers `"available"` after 90s of
polling, and **both** projection tables — `seat_availability` and `price_view` — are **empty (0
rows)** while the write side holds the bookings and tickets the saga created.

What is established:
- The write path is correct: the `reservations` row reads `state = confirmed`.
- The consumer slices are up: `ProjectSeatSold` and `MarkSeatSold` both report 3/3 `ACTIVE`.
- The wiring is correct in the artifacts: `BuyTicket.manifest` carries
  `publish.topic.0.topicName=seat-sold` and `ProjectSeatSold.manifest` carries
  `reactive.0.category=subscription` / `reactive.0.topicName=seat-sold`.
- Neither the five fact consumers nor the 60s `SweepHolds` schedule ever produced a single
  interceptor log line, though every one declares one at `INFO` in `resources.toml`.
- `SweepHolds` **does** run when invoked over HTTP. So the slice bodies are fine; it is the
  reactive trigger path — subscriptions and scheduling alike — that never fires.

What was checked and **excluded**: the `system:cluster-events` stream does log offset gaps and
rejected batches, but those are confined to that one system stream and its backfill completes
(`applied 4 events, self CAUGHT_UP`). It is not the cause.

**The two halves have different causes — the single-cause framing was wrong.** Subscription
delivery and scheduled invocation share no dispatcher, executor, registry or lifecycle hook below
the point where each reads its manifest. They are two independent defects, each on its own
sufficient to produce total silence, which is exactly why they looked like one.

**Subscriptions — a sufficient cause is identified.** A topic address is namespaced from the
`groupId` and `artifactId` of whichever artifact is handed to the resolver, and the publisher and
the subscriber each hand it *their own slice* artifact. Two co-deployed slices therefore never agree
on an address:

```
BuyTicket publishes to  org.pragmatica.example.ticketing-buy-ticket:seat-sold:1.0.0
ProjectSeatSold listens on  org.pragmatica.example.ticketing-project-seat-sold:seat-sold:1.0.0
```

Matching is exact string equality, so the publish finds no subscribers, **succeeds, and delivers
nothing** — no error, no log line. Streams do not have this defect: they are namespaced from the
blueprint. The fix is to namespace topics the same way.

**But "sufficient" is not "operative".** That mechanism is proven — a unit test pointed at two
distinct artifacts fails at the current release tip — but the proof bypasses the manifest read, and
an empty manifest read would produce an *identical* symptom. Both could be true at once. Reading the
cluster's KV store settles it: a subscription entry **present** under the subscriber's own namespace
means the mismatch is the operative cause; **absent** means the manifest read returned nothing.

**Scheduling — still unknown.** Three candidates remain, one of which logs nothing at all. Not
guessed at here.

This is not the `forge-data` issue above — it reproduces on a cleanly converged cluster. Tracked as
[#1216](https://github.com/pragmaticalabs/pragmatica/issues/1216).

### Troubleshooting

- **`ERROR: aether-forge not found`** from `run-forge.sh` — Forge isn't installed, or its launcher
  isn't on `PATH` and isn't at `~/.aether/bin/aether-forge` either. Re-run the installer from
  "Install the toolchain" above. Note this is the *only* thing that still comes from outside Maven
  Central; a failure here is never a missing dependency.
- **`jbct: command not found`** — the installer appended `~/.jbct/bin` to your shell rc but the
  current shell predates it. Open a new shell, or run `export PATH="$HOME/.jbct/bin:$PATH"`.
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
- **Every route 404s despite a clean deploy** — you almost certainly re-ran Forge over an existing
  `~/.aether/forge-data`. `rm -rf ~/.aether/forge-data` and start again; see "Re-running Forge" above.
  Rebuilding the slice does **not** clear this — the stale state is the cluster's, not the build's.
- **Some routes work and others 404 on the same cluster** — same cause. Check
  `curl -s :8888/api/slices/status` for instances stuck in `LOADING`/`UNLOADING`; but note that a
  fully `ACTIVE` slice can also have a missing route, so slice state alone does not settle it.
- **401 `no security mode is configured`** — the call needs `-H "X-API-Key: local-dev-insecure-do-not-use"`, or
  `aether.toml` is missing its `[app-http]` block. See "Enable authentication" above.
- **500 `Type mismatch: expected Request, got unknown`** on `/events/open/<e>` or
  `/events/cancel/<e>` — send `-d '{}'`. A path-parameter-only POST needs a body frame to bind.
- **`aether.toml`'s `[database]` block missing, commented out, or pointing at the wrong
  `async_url`** — every `@PgSql` slice fails to provision its store. It ships enabled by default; see
  "Start the infrastructure" above and restore it to match `start-postgres.sh`'s connection string
  if it was changed.

### Verifying all of this from a bare Linux image

```bash
./docker/verify-from-scratch.sh
```

Builds `ubuntu:24.04` + JDK 25 + Maven, starts PostgreSQL, installs the toolchain, builds this
project, boots the cluster and **asserts an HTTP status on every documented route**, ending with a
purchase that must return a receipt and a no-credential call that must be refused. It prints
`ALL CHECKS PASSED` or names the step that failed.

It deliberately mounts **no `~/.m2` and no `~/.aether` from the host**, and **asserts the local
Maven repository is empty (0 files) before building**. That assertion is the entire point: a
clean-room check with a warm cache measures the wrong thing and looks exactly like success — a
dependency that only ever resolved from a developer's populated `~/.m2` goes green locally while a
stranger's build cannot work at all.

The app container shares the PostgreSQL container's network namespace, so `localhost:5432`,
`localhost:9100` and the app's own ports mean the same thing inside the container as they do on a
laptop, and no configuration is rewritten for the container's benefit.

---

## The design

### The telescope, as packages

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
— narrow interfaces, because no slice there needs more than one method. The composition is the book's
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

### Access control and error status

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
  diagnostic pointing at the slice. Fixed upstream in `ResourceQualifierModel.variableSafeConfigSection()`,
  which now maps every code point failing `Character.isJavaIdentifierPart` to `_`; because that
  mapping is not injective (`a-b` and `a_b` collapse onto one name) the generator additionally
  de-collides issued names with a numeric suffix — three regression tests cover the hyphen, the
  separator collision, and the plain dotted form. **The workaround here is removed:** all eight
  interceptor sections are hyphenated again (`[cache.availability.seat-status]`,
  `[log.quote.project-price]`), so `resources.toml` is once more consistent with its topic and
  scheduling sections. Only the local variable name is sanitized — the `provide(Type.class, "…")`
  literal still carries the section verbatim, so resolution is unchanged.
- **Fixed since rc1, verified here:** migrations are auto-discovered from `V*__*.sql` (the
  `schema/migrations.list` manifest this project used to carry is deleted); text-block `@Query`
  emits correctly and the stores use it throughout; `[errors] strict = true` turns an unmapped
  `Cause` into a build failure.
- **Still true, still shaping the design:** one parameter per slice method (the JBCT idiom anyway);
  no data-modifying CTEs in `@Query` — now a clear compile error rather than silent mis-validation;
  `@Notify` and the interceptor factories live in separate artifacts; the forge `--blueprint`
  coordinate form. **`Promise.all` still caps at 15** in core — the slice-processor's `BatchedAll`
  batches a *generated* factory's transitive dependencies beyond that, but the cap is unchanged for
  code you write by hand.
- **Interceptors are adoptable only at the slice boundary.** 3 `LOCAL` caches on the read slices and
  5 logging interceptors on the fact consumers are wired. Payment retry / circuit-breaking is **not**
  adoptable — see [`docs/AETHER-WISHLIST.md`](docs/AETHER-WISHLIST.md) for why it would open the
  breaker on a hot event.

These are the friction a real telescope-shaped app finds that a HelloWorld never will.

A consolidated, prioritized DX wish list for the Aether toolchain — distilled from building this app —
lives in [`docs/AETHER-WISHLIST.md`](docs/AETHER-WISHLIST.md).

## Not covered here

- **Production deployment** (the `Main` node, cloud provisioning, Hetzner/other cloud targets) —
  outside this repo's scope; see the pragmatica monorepo's `aether/docs/` for that.
- **The `aether` CLI** (`~/.aether/bin/aether`) — used for operating a running cluster (schema
  migrations, deployment control); not needed to just run this project locally.
- **Embedding Ember directly** (`Ember.cluster(n).withH2()...start()` in your own JVM, no dashboard) —
  Forge is Ember plus a dashboard and load/failure tooling; see the pragmatica monorepo's `aether/`
  module docs if you want the bare library instead.

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE). See [`NOTICE`](NOTICE) for attribution.
