# Ticketing Platform — Aether Slice Project

An **event-ticketing** platform built as **Aether slices**, designed with **PFD (Process-First
Design)** and implemented with **JBCT (Java Backend Coding Technology)** on the **Aether distributed
runtime**.

> Global rules (git, delegation, challenge mode, consult-before-action, ndx) live in
> `~/.claude/CLAUDE.md`. JBCT patterns are owned by the `/jbct` skill and the `jbct-coder` /
> `jbct-reviewer` agents. Aether slice patterns are owned by the `aether-coder` skill. This file is
> for **project-specific facts and the glue between those sources** — it does not re-teach JBCT or Aether.

The `src/` tree now **is** the product: the **23 single-use-case slices** of the PFD telescope in
`org.pragmatica.example.ticketing` (subsystems `booking`, `pricing`, `eventmanagement`,
`availability`, `quote`). The HelloWorld scaffold has been removed.

---

## 1. The three layers — PFD → JBCT → Aether

| Layer | Role | Authoritative source |
|-------|------|----------------------|
| **PFD** | Design methodology (language-neutral): *what the software does* | `../coding-technology/book-pfd/` |
| **JBCT** | Java implementation of PFD design | `/jbct` skill, `jbct-coder` agent |
| **Aether** | Distributed runtime the slices deploy to | `aether-coder` skill |

The handoff: **PFD designs the process → JBCT writes the Java → Aether runs it.** PFD stops the
moment design becomes Java idiom; JBCT realizes the six PFD properties in `Result`/`Option`/`Promise`
code; Aether wraps that code in a `@Slice` and provisions its resources.

**Aether is a runtime, not a microservices framework.** Slices are units of deployment that the
runtime composes (in-process or across nodes) without changing business code — do not reason about
them as standalone services.

---

## 2. PFD in one screen

PFD's thesis: **the unit of design is the process, not the entity.** Design what happens (trigger →
outcome); data precipitates from processes, never modeled first. Read `book-pfd/foundations.md`
(canonical vocabulary) and `book-pfd/introduction.md` before designing anything non-trivial.

- **Telescope (altitudes):** use case → workflow → subsystem → system. Each altitude is a Leaf to the
  one above and a package level below. (`spiral-1..4-*.md`)
- **Six properties** of a process: trigger, typed input, typed output, **typed failures** (closed,
  enumerable set — never exceptions), steps, dependencies.
- **Four shapes:** `T`, `Option<T>`, `Result<T>`, `Promise<T>`.
- **Six patterns** (compose with these only; one per function): Leaf, Sequencer, Fork-Join,
  Condition, Iteration, Aspects.
- **Growing context:** thread a record through steps that accumulates knowledge; short-circuit on
  first failure.
- **Change-driver cohesion:** group by "what one change forces *all and only* these to change together".
- **Recovery triple:** BER (compensate / saga), FER (degrade forward), design-out (make invalidation
  impossible). Choose deliberately, per process.
- **Architecture comes last:** Phase 4 (elicit SLOs/quality answers) → Phase 5 (derive a six-axis
  *architecture vector*) → Phase 6 (pick technology). Never trade process simplicity for performance.

### This project IS the PFD running example
`book-pfd/architecture-synthesis.md` walks **event ticketing at three scales** — independent venue,
regional platform, enterprise multi-tenant. The **enterprise profile** is our reference architecture:
subsystems **`booking`** (reserve→authorize→confirm, strict consistency, **BER** saga), **`pricing`**
(price-history + projections), and **`event-management`** run as **Aether slices on the unified
runtime**, with the **read path (availability/quote) split out and scaled on its own profile**.
Booking on a contended seat is **design-out**: idempotent hold serializes access, losing attempts
fast-fail with `SeatUnavailable`. Read that section before designing booking/pricing/availability.

---

## 3. The Aether slice contract (essential facts)

A live slice (`src/main/java/org/pragmatica/example/ticketing/eventmanagement/lifecycle/createevent/CreateEvent.java`)
is the canonical shape:

- Interface annotated **`@Slice`** (`org.pragmatica.aether.slice.annotation.Slice`).
- **Factory method**: `static`, lowercase-first name matching the interface (`createEvent()`),
  returns the interface — typically a local `record … implements <Iface>` holding `execute` plus its
  named step helpers (a trivial single-expression slice may return a lambda instead).
- **Every method returns `Promise<T>`.** Use `.async()` to lift a `Result` into a `Promise`.
- Request/response are **nested `record`s**; validation lives in a `valid…(...)` factory returning
  `Result<T>` via `Verify.ensure(...)` (parse-don't-validate).
- Errors are a **sealed `interface … extends Cause`** hierarchy, one record per failure.
- Resources are **injected as factory parameters**: `static CreateEvent createEvent(@PgSql EventStore store) {…}`.
- **`@Codec` is never written** — the slice processor generates serialization codecs automatically.

**Routing** (`src/main/resources/<pkg>/routes.toml`): maps HTTP → slice methods. Has `prefix`,
`[routes]` (one per public method), and `[errors]` mapping `Cause` types → HTTP status (`default`
required, typically 500; patterns like `HTTP_422 = ["*Invalid*"]`). Every public method needs a
route; every error type needs a status.

**Slice config** (`src/main/resources/slices/<Name>.toml`): `[blueprint] instances = N`.

Full reference: `aether-coder` skill — `patterns/slice-contract.md`, `patterns/error-modeling.md`,
`patterns/inter-slice.md`, `deployment/routes-toml.md`.

---

## 4. Aether Store = type-safe PostgreSQL persistence (`@PgSql`)

**"Aether Store" is the `@PgSql` + `pg-codegen` persistence abstraction — NOT a key-value store and
NOT the runtime's internal consensus KV-store.** For all application/business data (events, seats,
bookings, prices) you write a `@PgSql` interface; `pg-codegen` generates the implementation and
**validates every query against the schema at compile time**. (The consensus KV-store holds
runtime infra state only — blueprints, routes, config — and is never used for app data.)

```java
@PgSql                              // org.pragmatica.aether.resource.db.PgSql
public interface BookingStore {
    record SeatRow(long seatId, String status) {}   // record; camelCase ↔ snake_case columns

    @Query("SELECT seat_id, status FROM seats WHERE seat_id = :seatId")   // :name binds by param name
    Promise<Option<SeatRow>> findSeat(long seatId);

    @Query("INSERT INTO bookings (seat_id, user_id) VALUES (:seatId, :userId)")
    Promise<Unit> insertBooking(long seatId, long userId);
}
```

- **Return types by cardinality:** `Promise<Option<Row>>` (one-or-none), `Promise<List<Row>>` (many),
  `Promise<Unit>` (write), `Promise<Long>`/`Promise<Boolean>` (scalars). Single-row that must exist:
  `Promise<Row>`.
- **`@Query`** = `org.pragmatica.aether.pg.codegen.annotation.Query`; named params `:name` bind to
  the method parameter of the same name (case-sensitive). Convention methods (`findBy…`, `countBy…`,
  `existsBy…`, `deleteBy…`, `OrderBy…Asc/Desc`, `insert`, `save`) also work without `@Query`.
- **Row types are plain `record`s** — no JPA annotations, no getters, no hand-written `RowMapper`.
- A bad table/column/type in any `@Query` is a **`[PG-VALIDATE]` compile error**, not a runtime
  failure — the migration must exist before the query compiles.
- **`@Sql` (`SqlConnector`) vs `@PgSql`:** `@Sql` is generic, runtime SQL needing a hand-written
  `RowMapper` + explicit `transactional(...)`, no compile check — use only for dynamic SQL,
  multi-statement transactions, or non-Postgres. **Default to `@PgSql`.**
- The pom already wires `pg-codegen` as an annotation-processor path — don't re-add it.

Reference: `aether-coder` skill `resources/database.md`. Real-time DB change events
(LISTEN/NOTIFY) via a method-level `@ResourceQualifier(... PgNotificationSubscriber ...)` →
`resources/pg-notifications.md`. (`@Notify` is email/SMS, unrelated.)

---

## 5. Schema & migrations — mind the two directories

| Path | Purpose | Who reads it |
|------|---------|--------------|
| `src/main/resources/schema/V001__*.sql` | **Canonical** Flyway-style migrations | pg-codegen (compile-time validation) **and** Aether (applied automatically on deploy) |
| `schema/init.sql` | Local-container bootstrap only | `start-postgres.sh` |

- **Keep all canonical DDL in `src/main/resources/schema/`.** If a table lives only in
  `schema/init.sql`, compile-time query validation is blind to it.
- Naming: `V{NNN}__{description}.sql` — **double underscore**. Sequential (`V001__`, `V002__`).
  Make idempotent (`CREATE TABLE IF NOT EXISTS`).
- Aether applies pending migrations on blueprint deploy; a slice does not activate until its
  migrations complete (`schema_required = false` opts out per blueprint).
- Multi-datasource: subdirs map to config sections (`schema/analytics/` → `[database.analytics]`).

Reference: `aether-coder` skill `deployment/schema-migrations.md`.

---

## 6. Config — `aether.toml`

DB is **disabled by default** (the `[database]` block ships commented out). To enable:

```toml
[database]
async_url = "postgresql://postgres:postgres@localhost:5432/forge"   # this project's key form

[database.pool_config]
min_connections = 4
max_connections = 20
connection_timeout = "30s"
idle_timeout = "10m"
max_lifetime = "30m"
```

- This project's authoritative key is **`async_url`** (not the `host/port/username/password` form in
  the generic skill docs — match the existing file).
- Secrets: `${secrets:path}` — never hardcode credentials in committed config.
- `forge.toml` `[database] enabled` toggles DB for the local Forge cluster.

---

## 7. Build, local dev, deploy

```bash
mvn clean install            # build (annotation processors: slice-processor + pg-codegen)
mvn test                     # unit tests
jbct check src/main/java     # format + 41 lint rules (also: jbct format / jbct lint)

./start-postgres.sh          # local PostgreSQL 17 (docker/podman), db=forge, applies schema/init.sql
./run-forge.sh               # build + start 5-node local cluster (dash :8888, app :8070, mgmt :5150)
./generate-blueprint.sh      # produce target/blueprint.toml from slice manifests

./deploy-forge.sh            # deploy → local
./deploy-test.sh             # deploy → test env
./deploy-prod.sh             # deploy → production
```

- **Toolchain:** Java 25, Maven 3.9+. Pragmatica Lite / Aether / jbct artifacts are pinned to
  **`1.0.0-rc2`** (the three `*.version` properties in `pom.xml`). **rc2 was published to Maven
  Central on 2026-07-16** — including the official `slice-processor:1.0.0-rc2` with both codegen
  fixes (route-import collision + codec FQN, PR pragmaticalabs/pragmatica#364), so the previously
  hand-patched local jar is retired. **Exception: `resource-api` and `resource-notification` did NOT
  make the release** — they must be locally installed from a `release-1.0.0-rc2` checkout
  (`mvn install` under `aether/resource`) or the project won't resolve. The `jbct` CLI at `~/.jbct`
  is **1.0.0-rc2** (upgraded 2026-07-17 from the Central `jbct-cli` dist; rc1 kept as
  `jbct.jar.bak-2026-07-17` — note `jbct upgrade` checks a stale registry and can't self-update).
- **basePackage:** `org.pragmatica.example.ticketing` (`jbct.toml`); line length 120, indent 4.
- **Delegate Maven/test runs to the `build-runner` agent** — keep verbose output out of main context.
  Never run inline `mvn`/`jbct` for anything beyond a one-line status check.
- Smoke test a running slice with `curl http://localhost:8070/api/...`; load-test with k6
  (`aether-coder` `deployment/k6-testing.md`).

---

## 8. Delegation model + jbct-coder briefing (important)

**Division of labor:** the `aether-coder` skill knows Aether; **`jbct-coder` knows JBCT but has ZERO
Aether knowledge** (grep-confirmed: `@Slice`, `routes.toml`, `@PgSql`, `@Query`, `aether.toml`,
`pg-codegen`, `@ResourceQualifier`, `blueprint` all absent from its definition). It reliably produces
JBCT idioms — `Result`/`Option`/`Promise`, factory methods, sealed `Cause`, parse-don't-validate,
the six patterns — but will **not** produce correct slice/persistence/routing code unless briefed.

**Workflow:** design with PFD (main thread) → for Java implementation, either drive the `aether-coder`
skill or delegate to `jbct-coder` **with the briefing block below** → review with `jbct-reviewer`
(JBCT compliance) plus an Aether check (routes/error-mapping/resource wiring) → `build-runner` to verify.

**Paste this Aether briefing into every `jbct-coder` delegation:**

```
AETHER CONTEXT (you have no built-in Aether knowledge — follow exactly):
- Slice: interface annotated @Slice; static factory, lowercase-first name == interface, returns the
  interface as a lambda. ALL methods return Promise<T>. Request/response are nested records. Errors
  are a sealed interface extends Cause. Resources are factory parameters.
- Persistence: declare a @PgSql interface with @Query("SQL with :namedParams") methods. Row types are
  plain records (camelCase fields ↔ snake_case columns). Returns: Promise<Option<Row>> / Promise<List<Row>>
  / Promise<Unit>. DO NOT hand-write SQL ResultSet mappers — pg-codegen generates them.
- DO NOT write @Codec (generated). DO NOT throw exceptions. DO NOT validate inside @PgSql methods —
  validation stays in the slice via Verify.
- Keep all JBCT idioms unchanged (Result/Option/Promise, sealed Cause, factory naming, parse-don't-validate).
- Files: <slice iface pkg/path>, <@PgSql store pkg/path>, routes.toml at <path>, migration at
  src/main/resources/schema/V0NN__<name>.sql. The @Query SQL must match that migration's columns.
- DO NOT touch aether.toml or blueprint unless told.
```

**Version note (two tracks, don't conflate):**
- **JBCT methodology / spec:** latest is **4.1.x** (`../coding-technology/book/`, changelog `4.1.2`,
  late-June 2026). The active (global) `jbct-coder` / `jbct-reviewer` agents are dated **2026-06-12 on
  Pragmatica Core `1.0.0-rc1`** — current on the Core API, only ~2 weeks behind the newest 4.1.x book
  edits. (The old project-local agent copies cited stale `2.1.1 / 0.9.10` and were removed so the
  session resolves to these current globals.) Structural patterns are stable; for the freshest
  methodology and exact API signatures the **`../coding-technology/book/`** + **`/jbct` skill**
  (Core `1.0.0-rc1`) + real `org.pragmatica.lang.*` types are authoritative.
- **Maven artifacts:** project pins **`1.0.0-rc2`** (the three `*.version` properties in `pom.xml`;
  on Maven Central since 2026-07-16 except `resource-api`/`resource-notification` — see §7).

---

## 9. Working rules (borrowed from pragmatica)

- **Triage before fixing.** Is the issue *local* (one wrong line) or *structural* (a missing/wrong
  abstraction that recurs)? Fix at the matching level. When triage flips an issue to structural
  mid-task, surface it with trade-offs before the big fix.
- **"Done" only when reconciled.** A spec/feature is done only when it is end-to-end (full path, no
  stubs), verified (tests written *and run* — show evidence), and reconciled section-by-section
  against the design. No placeholder `Option.empty()`, `TODO`, "not implemented", or "simplified for
  now" left in the path.
- **Test naming:** `method_scenario_expectation()` (e.g. `greet_validName_returnsGreeting`,
  `bookSeat_seatHeld_returnsSeatUnavailable`).
- **Validation checkpoint before calling a slice complete:** @Slice + factory + `Promise<T>` returns;
  routes.toml has every method + every error mapped + a `default`; every resource qualifier has a
  matching `aether.toml` section; migrations exist with sequential `V0NN__` names; unit tests cover
  validation + happy path + each failure; `jbct check` passes. (Full list: `aether-coder` SKILL.md
  "Self-Validation Checkpoint".)

---

## 10. Gotchas

- "Aether Store" means `@PgSql` persistence — not a KV store, not the consensus KV-store.
- Two schema dirs: canonical DDL → `src/main/resources/schema/V0NN__*.sql`; `schema/init.sql` is only
  the local-container bootstrap.
- `V001__name.sql` needs a **double** underscore.
- A wrong column/table in a `@Query` **fails the build** (`[PG-VALIDATE]`), not at runtime.
- DB is off until `[database]` is uncommented in `aether.toml`; this project's key is `async_url`.
- Never hand-write codecs or SQL row mappers; never throw business exceptions; resources inject via
  the factory's parameters.
- `jbct-coder` must be briefed on Aether every time (§8).
- **rc1→rc2 slice-toolchain gotchas (hard-won — `docs/DESIGN.md` §8–§10 has the full list; rc2
  statuses below are source-verified against the released jars, marked ⧗ where this repo hasn't
  exercised the change yet):** `migrations.list` — rc2 auto-discovers `V*__*.sql` from the schema
  dir, manifest now advisory ⧗ (we still ship one; harmless); `@Query` accepts **text blocks**
  (rc1 mis-emit fixed; this repo uses them) but data-modifying CTEs stay unsupported — now a clear
  compile error instead of silent mis-validation; **one-parameter slice methods** — rc2 auto-wraps
  multi-param methods in a generated `<Method>Request` ⧗, but one request record stays the JBCT
  idiom; the **15-transitive-dep `Promise.all` cap is gone** (rc2 auto-batches, fail-fast preserved) ⧗
  — the one-slice-per-use-case split + pub-sub facts (`SeatSold`/`SeatReleased`/`PriceChanged`)
  remain by design, not by cap; `@Notify` still needs the separate
  `org.pragmatica-lite.aether:resource-notification` provided dep (NOT published to Central — see
  Toolchain); still no `@Heartbeat`, but rc2 ships a real `Scheduled` (zero-param `Promise<Unit>`,
  interval/cron, KV-tracked) ⧗ — candidate for `SweepHolds`; a `resources.toml` must still declare
  every `@ResourceQualifier` config section (typed `Topic<T>` reduces the topic part). New rc2
  opt-in worth enabling: `[errors] strict = true` makes unmapped `Cause`→HTTP a build failure ⧗.
  Both slice-processor codegen bugs (route-import collision + codec FQN, PR #364) are fixed in the
  released rc2.
- The real product lives in `org.pragmatica.example.ticketing`, structured as the **PFD telescope**
  (system→subsystem→workflow→use case as packages): **23 single-use-case slices** (subsystems booking,
  pricing, eventmanagement, availability, quote), each one nested `Request`/`Response` + an
  `execute(Request)` method. The **write** subsystems (booking, pricing, eventmanagement) each share
  one per-subsystem `@PgSql` store; the **read** subsystems (availability, quote) use **per-use-case
  `@PgSql` interfaces** co-located with each slice (interface segregation — no read slice needs more
  than one method). The HelloWorld scaffold is gone.
  **Two** slice-processor codegen bugs were found+fixed (route-import collision +
  codec-FQN shadowing of injected-slice `Request`/`Response`); both fixes shipped in the released
  `slice-processor:1.0.0-rc2` on Maven Central (PR pragmaticalabs/pragmatica#364) — see
  `docs/DESIGN.md` §8.
