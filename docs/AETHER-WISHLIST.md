# Aether Runtime — DX Wish List

**From:** the ticketing posterchild (`org.pragmatica.example.ticketing`), built on Pragmatica/Aether/JBCT `1.0.0-rc2`.
**To:** the Aether runtime/toolchain maintainers.
**Why this exists:** this repo's stated purpose is "test our own tool" — build the book's full event-ticketing
platform as real Aether slices and surface the friction a HelloWorld never hits. This is that friction,
prioritized, with concrete reproductions from the build so each item is actionable.

> Framing for the whole list: **the core slice contract is genuinely good** — `@Slice` interface + static
> factory + `Promise<T>` + nested `Request`/`Response` + sealed `Cause`, resources as factory params, codecs
> you never write, and compile-time `@Query` validation. Almost all friction is at the **edges** (codegen,
> config, routing, pub-sub), not the core. Fixing the edges would make this a best-in-class backend DX.

## Priority summary

| # | Item | Component | Priority | Why |
|---|------|-----------|----------|-----|
| 1 | Error→HTTP **totality check** at compile time | slice-processor + http-routing | **P0** | silent 500s |
| 2 | Pub-sub **Promise honesty** (don't discard subscriber result) | infra-pubsub | **P0** | silent data loss |
| 3 | **Auto-discover migrations** (kill `migrations.list`) | pg-codegen | **P0** | silent skip |
| 4 | **VO↔column mapping** (use VOs in `@Query`, not raw types) | pg-codegen | **P1** | pervasive ceremony |
| 5 | `aether verify` — **whole-contract static check** | new mojo | **P1** | many late failures |
| 6 | **Typed** error→status (`@HttpStatus`), not glob-on-simple-names | routing | **P1** | fragile, drifts |
| 7 | **Scaffold/validate** the 4 TOMLs from annotations | maven plugin | **P1** | config sprawl |
| 8 | Support **text-block `@Query`** | pg-codegen | **P1** | forced concatenation |
| 9 | Forge archive must **bundle resource providers** + fail fast | forge | **P1** | can't run live |
| 10 | Ship **`@Scheduled`/`@Heartbeat`** | runtime | **P1** | workaround-forcing |
| 11 | **Shape-aware lint** (exempt transport/row/fact records) | jbct-lint | **P1** | ~76 false suppressions |
| 12 | **Canonical-name codegen** + adversarial fixtures | slice-processor | **P2** | (2 bugs already fixed) |
| 13 | Clear errors: **multi-param method**, **15-dep cap**, **data-modifying CTE** | several | **P2** | cryptic crashes |
| 14 | Tangential ideas (typed topics, observability aspect, test kit, …) | various | **P2** | future polish |

---

## P0 — silent-failure / correctness traps

### 1. Compile-time error→HTTP **totality** check
**Problem.** A slice's failure set is a *closed* sealed `Cause` hierarchy, but its HTTP mapping lives in a
separate `routes.toml` matched by **globs over simple names** (`HTTP_400 = ["*Blank*"]`). Nothing forces the
two to agree, so a new error type silently falls through to the `default` (500), and a stale pattern silently
matches nothing.
**Evidence (this session).** Adding `CreateEventError.MalformedOnSaleAt` and `Percent.NonPositive` compiled
green and would have returned **500 instead of 400** — caught only by a manual doc/code reconciliation audit,
not the compiler. Separately, `setprice/routes.toml` carried a dead `HTTP_404 = ["*PriceNotFound*"]` matching
zero causes (SetPrice only declares `StoreUnavailable`).
**Proposal.** At compile time, diff each routed slice's sealed `Cause` hierarchy against its `routes.toml`:
fail the build if any `Cause` record is unmapped, and warn if any pattern matches zero causes.
**Acceptance.** A slice with an unmapped failure record fails `mvn compile` with a message naming the record
and the `routes.toml` to edit. The two test cases above would both fail the build.

### 2. Pub-sub subscriber-result **honesty**
**Problem.** A subscriber is `Promise<Unit> execute(Fact)`, so developers reason about failure and write
`.recover(...)` — but `InMemoryPubSub.deliverToSubscribers(...)` **invokes the handler and discards the returned
Promise** (it always returns `Promise.success(unit())`). At-most-once, no redelivery/replay/DLQ, and it's the
*only* `PubSub` impl. So a transient store error during convergence **silently desyncs the projection forever**,
and `.recover`-vs-not is moot.
**Evidence (this session).** A 5-finder review flagged "the `.recover` drops facts" as a bug across all five
projection/convergence slices; verifying the runtime showed the framing was *wrong for this impl* (nothing is
ever redelivered) — but that's the deeper problem: **the API shape promises a guarantee the runtime doesn't
keep.** The framework leans hard on projections/convergence (a first-class telescope concept) yet can't deliver
the durability that pattern assumes.
**Proposal.** Pick one and make the contract honest: **(a)** honor the returned Promise — at-least-once with
retry/backoff/DLQ + an idempotency-key resource; or **(b)** change the signature so it isn't a discarded
`Promise`. Then ship a durable `PubSub` impl, and ideally a built-in **projection** abstraction
(checkpoint/replay/idempotent upsert) since that's the encouraged style.
**Acceptance.** Either a subscriber failure visibly triggers redelivery/DLQ, or the signature no longer implies
one. A doc table states the delivery guarantee per impl.

### 3. **Auto-discover** migrations
**Problem.** pg-codegen probes a *hardcoded* filename list; a migration whose name isn't in it is **silently
skipped**, so every table in it reads "not found" at compile time even though the DDL exists. The workaround is
a hand-maintained `src/main/resources/schema/migrations.list`.
**Evidence (this session).** `V002__eventmanagement.sql` etc. were invisible until `migrations.list` was added;
the failure mode (compile error "table not found" for a table that's right there) is maximally confusing.
**Proposal.** Glob the schema dir(s) for `V*__*.sql` in version order; drop the manifest (or make it optional/
override-only). If a manifest stays, a migration on disk but absent from it should **warn**, not silently skip.
**Acceptance.** Deleting `migrations.list` leaves the build green via auto-discovery.

---

## P1 — high-leverage DX

### 4. VO↔column mapping — use value objects in `@Query`, not raw types
**Problem.** `@PgSql` methods and row records must use raw `UUID`/`String`/`long`, so every slice hand-unwraps
VOs going in and re-parses them coming out.
**Evidence (this session).** `store.findStatus(seatId.value().value())` (SeatId→Uuid→UUID) on the way in;
`StatusRow(String status)` then `SeatState.seatState(row.status())` on the way out; the `SeatState.dbValue()` /
`seatState(String)` dance at every boundary; the recent `status→state` rename had to touch the column, the
`@Query`, the row field, and the bind param in lockstep — all because the boundary is stringly.
**Proposal — a pure, explicit `PgRepr<T,P>` (see the deep-dive in §A below).** Let a VO register the two
functions it *already has* — `lower: VO→P` (accessor, total) and `lift: P→Result<VO>` (factory, fallible) — once,
and pg-codegen generates the unwrap/decode. `findState(SeatId seatId)` and `record StateRow(SeatState state)`
just work. **Non-magic by construction:** explicit one-line declaration, compile-time codegen (not reflection),
compile-time type check (Repr's `P` must match the column's SQL type), and decode failures surface as a typed
`Promise` cause — never an exception or silent default.
**Acceptance.** A `@Query` method taking `SeatId` and returning a record with a `SeatState` field compiles,
binds the UUID, and decodes the enum; a missing/ambiguous/type-mismatched `PgRepr` is a compile error.

### 5. `aether verify` — validate the whole slice contract statically
**Problem.** The slice contract spans `@Slice` + sealed `Cause` + `@Query` + `@ResourceQualifier` + four TOMLs,
and the cross-file invariants are only discovered at runtime/deploy or by manual audit.
**Evidence (this session).** Across the build I had to *manually* confirm: every public method routed, every
error mapped + a `default`, every `@ResourceQualifier` has a `resources.toml` section, every `@Query` column
exists in a migration, every slice has a `slices/<Name>.toml`. All statically checkable; all currently manual.
**Proposal.** A single `aether verify` mojo/goal that asserts the full contract and reports every gap with a
fix hint. Item #1 is a subset of this.
**Acceptance.** Running it on a slice missing any of the above fails with a precise, located message.

### 6. Typed error→status mapping (`@HttpStatus`), retire glob-on-simple-names
**Problem.** Glob matching over error *simple names* is fragile in exactly the way that has already bitten
codegen (simple-name collisions are guaranteed by the per-VO `Blank`/`Malformed` idiom). Rename an error → it
silently stops matching.
**Proposal.** Allow `@HttpStatus(422)` on a `Cause` record (or the sealed interface, with per-record override),
and/or generate the exhaustive map. Keep `routes.toml` for prefixes/methods, derive the error map from types.
Pairs naturally with #1 (totality).
**Acceptance.** Status is declared next to the failure; renaming the record can't silently change its status.

### 7. Scaffold/validate the four config files from annotations
**Problem.** `aether.toml`, `resources.toml`, `routes.toml`, `slices/<Name>.toml` each carry manual sync points
("every `@ResourceQualifier` needs a `resources.toml` section", "every slice needs a blueprint", "every method
needs a route"); a forgotten section is a cryptic runtime/load failure.
**Proposal.** Generate starter `routes.toml`/`slices/*.toml` from the annotations (`aether scaffold`), and
validate deltas in #5. Consider consolidating `routes.toml` + `slices/<Name>.toml`.
**Acceptance.** Adding a slice scaffolds its blueprint+routes stub; a missing resource section is a build error,
not a deploy surprise.

### 8. Support text-block `@Query` — ✅ FIXED in released `1.0.0-rc2`
**Was.** Multi-line SQL via `"""…"""` mis-emitted (raw newlines into a string literal), forcing
`"…" + "…"` concatenation everywhere.
**Verified fixed (2026-07-17).** The released rc2 pg-codegen emits the SQL constant with escaped
`\n` (compilable literal), named params still rewrite to positional — text-block `@Query` validates
and runs identically to the concatenated form. This repo's stores now use text blocks throughout.
*(Positive finding to preserve: a single-statement `INSERT … SELECT coalesce(max(version),0)+1 … RETURNING`
with an aggregate **validates fine** in pg-codegen — used for atomic version allocation. Keep that working.)*

### 9. Forge archive: bundle resource providers + fail fast
**Problem.** The forge archive shipped the `@Http`/`@Notify` **API** but not the provider impls
(`JdkHttpClient`/`SmtpNotificationSender`), so slices using them **fail to load** live — opaquely.
**Evidence (this session).** `BuyTicket`/`CancelTicket` couldn't deploy live on the rc2 forge (the other ~21
slices would); the archive self-reported `0.20.0` and its launcher symlink mis-resolved its JRE.
**Proposal.** Bundle (or clearly declare) the provider modules in the archive; a slice needing `@Http` with no
provider should fail at deploy with **"no provider for @Http"**, not an opaque load failure. Fix the
version/launcher drift.
**Acceptance.** A clean rc archive runs the full 23-slice blueprint end-to-end.

### 10. Ship `@Scheduled` / `@Heartbeat`
**Problem.** rc1/rc2 have no scheduled-trigger annotation, so periodic work (a hold-expiry sweep) must be
modeled as an HTTP endpoint — which *misrepresents the design* (it's an Iteration/cron concern, not an API).
**Evidence (this session).** `SweepHolds` is an HTTP slice purely because `@Heartbeat` doesn't exist.
**Proposal.** A scheduled-trigger resource (`@Scheduled("*/5 * * * *")` / `@Heartbeat`) wiring a zero-input
slice method.
**Acceptance.** The sweep attaches to a schedule with no HTTP route.

### 11. Shape-aware lint (stop false-positiving on framework shapes)
**Problem.** `JBCT-VO-01` ("record needs a factory") fires on records that legitimately have none — slice
`Request`/`Response`, `@PgSql` row records, `shared.event` facts, growing-context stage records. `JBCT-SEQ-01`
("chain > 5 steps") lands on the local **impl-record declaration** and sums every chained call across the whole
record body (a measurement artifact).
**Evidence (this session).** ~**76** suppressions were needed to reach 0 warnings — 59 VO-01 (all transport/row/
fact/DTO records) + 17 SEQ-01 (impl-record decls). Zero were real gaps.
**Proposal.** Teach the linter the framework's shapes: auto-exempt `@Slice`-nested `Request`/`Response`,
`@PgSql` row records, and `@ResourceQualifier` fact records from VO-01; make SEQ-01 count a single method's
chain, not a record body. Net: those ~76 suppressions disappear.
**Acceptance.** The ticketing repo passes `jbct check` with **0 warnings and 0 suppressions**.

---

## P2 — robustness, clarity, and the long tail

### 12. Canonical-name codegen + adversarial fixtures *(2 instances already fixed — capture the lesson)*
Both codegen bugs found this session were the same root cause: emitting *simple* type names where Java shadows
them. **(a)** A factory's inner record `implements BuyTicket` shadowed the injected `QuotePrice.Request`/
`Response` (JLS §6.5.5.2). **(b)** Duplicate single-type imports for two error types sharing a simple name. Both
fixed via FQN emission (PR pragmaticalabs/pragmatica#364). **Lesson:** default generated code to fully-qualified
references *everywhere*, and add permanent fixtures for the two situations the book's idioms *guarantee* —
"slice injects slice, both with `Request`/`Response`" and "two errors, same simple name." Also: when generated
code fails to compile, the error lands far from the cause — **attribute generated-code errors back to the slice
+ source line.**

### 13. Replace cryptic crashes with clear errors
- **Multi-param slice method** crashes route generation (`parameterType()` assumes one). Either support multi-
  param (bind to path/body) or emit "a slice method takes exactly one request record." *(The one-param rule is
  arguably good design — it forced clean Request records — but it should be enforced with a message, not a crash.)*
- **15 transitive-dep cap** surfaces as `Too many dependencies (N) for Promise.all()`. Document it, chunk
  internally, or raise it. *(Interesting: this cap is what made interface-segregation/synchronous-read tradeoffs
  legible — a constraint that shaped architecture. Keep the constraint, fix the message.)*
- **Data-modifying CTEs** mis-validate silently in pg-codegen — make it a clear "unsupported" error.

### 14. Tangential / speculative (explicitly low-probability, as requested)
- **First-class typed topics.** `Topic<SeatSold>` instead of a bare kebab-string `config="seat-sold"` (plus the
  historical `messaging.`-prefix confusion) → publisher/subscriber type-safe by construction, topic strings in
  one place.
- **One descriptor, many boundaries.** Generalize the `PgRepr` of #4 to a neutral `Repr<T,P>` that *also* drives
  HTTP path/body binding and fact codecs — so `Request(SeatId seat)` auto-lifts the path segment (parse failure
  → typed 400) from the *same* one-line declaration. Kills "raw types at every boundary" holistically.
- **Idempotency/dedup as a subscriber resource** (since convergence/projection is *the* pattern).
- **Observability as a slice-boundary aspect.** The slice boundary is a natural trace span; a built-in per-slice
  tracing/metrics aspect would deliver the "uniform observability (Aspects)" the design already promises.
- **Slice test kit.** Spin a slice with fakes/testcontainers + a typed client, so end-to-end slice tests don't
  need the full forge.
- **Schema-derived row types.** Derive `@PgSql` row records from the schema (or diff field↔column names with a
  clear message) — the row field must equal the column name, a coupling the `status→state` rename just exercised.
- **Naming-collision lint.** The `SeatStatus` slice vs `SeatState` enum homonym forced `import static` gymnastics;
  a warning would help (latent in any slice-per-use-case + shared-VO codebase).
- **Rename "Aether Store."** The term means `@PgSql` persistence — *not* a KV store, *not* the consensus KV —
  and `CLAUDE.md` literally has to warn about it. A clearer name removes a standing conceptual tripwire.
- **Local slice-processor patched-jar fragility.** The build depends on a locally-installed patched
  `slice-processor:1.0.0-rc2`; it went stale mid-session and re-triggered an already-fixed codegen bug until
  rebuilt. Publishing the official rc2 (or a clear "your processor jar predates fix X" check) removes this.

---

## §A — VO↔column mapping, in detail (the design behind #4)

**Litmus test for "not magic":** can a developer (1) ⌘-click from the VO to the exact mapping, (2) read the
*generated* bind/decode as plain Java, and (3) get a **compile error**, not a runtime surprise, when it's wrong?
If yes, it's sugar over code you'd hand-write. Prior art that passes this test: Rust `sqlx` (`Encode`/`Decode`),
Scala doobie (`Get`/`Put`) — all explicit, compile-time, per-type codecs *precisely* to avoid reflective magic
(the Hibernate cautionary tale).

**The core.** A VO↔column mapping is the pair the VO *already provides*:
- `lower : VO → P` — total, the accessor (`SeatId::raw`, `SeatState::dbValue`).
- `lift  : P → Result<VO>` — fallible, the parse factory (`SeatId::seatId`, `SeatState::seatState`).

```java
// Pure descriptor — two function refs, zero runtime/reflection. Lift honors parse-don't-validate.
public record PgRepr<T, P>(Fn1<P, T> lower, Fn1<Result<T>, P> lift) {
    public static <T, P> PgRepr<T, P> of(Fn1<P, T> lower, Fn1<Result<T>, P> lift) { … }
}

static final PgRepr<SeatId, UUID>      SEAT_ID    = PgRepr.of(SeatId::raw,       SeatId::seatId);
static final PgRepr<SeatState, String> SEAT_STATE = PgRepr.of(SeatState::dbValue, SeatState::seatState);
```
```java
@PgSql interface SeatStatusStore {
    record StateRow(SeatState state) {}                                  // lifted from the TEXT column
    @Query("SELECT state FROM seat_availability WHERE seat_id = :seatId")
    Promise<Option<StateRow>> findState(SeatId seatId);                  // lowered to UUID for binding
}
```

**Why it's not magic.**
1. **Explicit, opt-in.** No inference from record shape — even trivial `SeatId(Uuid value)` writes the one-line
   `PgRepr`. That line *is* the explicitness.
2. **Codegen, not reflection.** The generated impl contains literal `ps.setObject(i, seatId.raw())` and
   `SeatState.seatState(rs.getString("state"))` — open and read it.
3. **Compile-time validated.** Missing `PgRepr<SeatId>` → compile error; the Repr's `P` must match the column's
   SQL type (the existing `[PG-VALIDATE]`, extended to VOs); two Reprs for one VO → ambiguity error.
4. **Fallibility surfaced, never swallowed** (below).

**The honesty crux — the two directions are asymmetric.**
- **Binding (params) is total → pure win.** `SeatId→UUID` can't fail; just call `lower`.
- **Decoding (row fields) is fallible → flows through the channel you already handle.** A column value that
  doesn't parse fails the row decode with a *typed* cause (`RowDecode(column, underlyingCause)`) on the `Promise`
  you're already on — not an exception, not a silent default. That's parse-don't-validate finally applied to the
  DB boundary (an untrusted input like any other). Offer `PgRepr.trusted(lower, infallibleLift)` as a *visible*
  escape hatch for round-tripped values.

**Where it lives (fits this project's `shared/`-purity rule).** Make `PgRepr` a pure descriptor (no Aether/JDBC
dependency) so a VO can declare its own representation without `shared/` importing persistence — a *domain*
statement ("a `SeatId` is a `UUID`"), not a persistence one. Alternatively, a persistence-side `@PgReprs`
registry keeps VOs untouched. Either is discoverable + compile-checked.

**Honest edges (don't oversell).**
- **Multi-column VOs.** `Money(long amountMinor, Currency currency)` and `SeatLocation` map to 2–3 columns; a
  single-column `PgRepr` doesn't apply. Offer `PgComposite<Money>(…)` or keep decomposing at the boundary. The
  sugar targets the single-wrapper 90% (IDs, enums, `Percent`).
- **Row reads can now fail on decode** — a *feature* (surfaces corruption) but a new failure mode; the `trusted`
  hatch exists for when you accept the risk.
- **Keep it opt-in per VO.** Inferring mappings from record shape "to be helpful" is the exact magic to avoid —
  and it breaks on the first multi-field VO.

**Acceptance.** `findState(SeatId)` returning a `SeatState`-field record compiles, binds the UUID, decodes the
enum; missing/ambiguous/type-mismatched `PgRepr` is a compile error; a non-parsing column value yields a typed
`Promise` failure, not an exception.

---

## Addendum (2026-07-17) — what the released rc2 actually addressed

Source-level audit of the published rc2 jars/sources (+ `v1.0.0-rc2` changelog). "Landed" = verified
in the released sources; items marked *(not yet exercised here)* haven't been driven by this repo's
build/tests yet.

| # | Item | rc2 status |
|---|------|-----------|
| 1 | Error→HTTP totality | **Landed** — `ErrorMappingValidator` (totality + dead-pattern/reference); unmapped `Cause` fails the build only under `[errors] strict = true`, warns by default *(not yet enabled here)* |
| 2 | Pub-sub honesty | **Partial** — per-consumer-group `ErrorStrategy{RETRY,SKIP,STALL}` + `dead-letter` stream + `max-retries` in stream config; runtime dispatch not verifiable from published sources |
| 3 | Auto-discover migrations | **Landed** — `SchemaLoader` globs `V*__*.sql` from the directory; `migrations.list` now advisory (warning on drift; manifest only needed for jar-packaged schemas) |
| 4 | VO↔column mapping | **Landed** — `ValueMapping<T,P>` (absorbed the `PgRepr` proposal, #397): VO convention `static ValueMapping<Vo,P> valueMapping()`, drives row decode AND `:param` binding, plus HTTP path/query binding |
| 5 | `aether verify` | **Can't tell** — no verify/validate surface found in the published cli pom; needs a runtime check |
| 6 | `@HttpStatus` | Not landed — error→status still routes.toml pattern-globs |
| 7 | Config scaffolding | Not landed — but typed `Topic<T>` (#396) removes much of the manual `resources.toml` topic wiring |
| 8 | Text-block `@Query` | **Landed** — verified empirically in this repo (see §8 above) |
| 9 | Forge bundles providers | Not landed — no forge submodule depends on `resource-http`/`resource-notification`; live E2E still blocked |
| 10 | `@Scheduled`/`@Heartbeat` | **Partial** — `Scheduled` is real (zero-param `Promise<Unit>` methods, interval or cron, KV-tracked state; candidate to replace the operator-triggered `SweepHolds` endpoint). No `@Heartbeat` |
| 11 | Shape-aware lint | **Partial** — JBCT-VO-01 now exempts `@Slice`/`@PgSql` framework shapes (this repo's `@SuppressWarnings("JBCT-VO-01")` may be removable); no test-tree awareness |
| 12 | Canonical-name codegen | **Landed** — both PR #364 fixes confirmed in released sources |
| 13 | Clear errors | **All three landed** — multi-param slice methods now auto-generate a wrapper `<Method>Request` (no more crash); >15 deps auto-batch (`BatchedAll`, fail-fast preserved — the cap is gone); data-modifying CTEs now a clear located compile error |
| 14 | Typed topics | **Landed** — `Topic<T>` in slice-api (envelope 1005→1006) |

Also new in rc2, not on the list: `RateGuard` (injectable backpressure resource +
`ResourceCapacityExhausted`), aspect-level observability config surface, `DeferredSliceInvokerFacade`,
stream `TierAwareRetention`, header-mode API versioning (#198), `produces`/`consumes` media types (#339).

**Adoption findings (this repo, 2026-07-17):** (a) `@ResourceQualifier` is `@Target(ANNOTATION_TYPE)`
only — a `Scheduled` method needs a custom qualifier annotation (we added `@SweepSchedule`), same
pattern as the subscription qualifiers. (b) Typed `Topic<T>` constants resolve fine in the
slice-processor, but `jbct-maven-plugin generate-blueprint` still validates one `resources.toml`
section per *resolved* topic name — the kebab-case sections must stay alongside the constants.
Wish: teach blueprint validation the typed form so the redundant sections can go.

---

## Addendum (2026-07-17) — rc2 release completeness

`1.0.0-rc2` reached Maven Central on 2026-07-16, but the **`aether/resource` subtree was not
published — deliberately**: `aether/resource/pom.xml` at `v1.0.0-rc2` sets
`<skipPublishing>true</skipPublishing>` (central-publishing-maven-plugin), inherited by all 13
resource modules. Result: `resource-api` and `resource-notification` 404, and no published rc2 jar
contains `org.pragmatica.aether.resource.*` (`@PgSql`, `@Http`/`HttpClient`,
`@Notify`/`NotificationSender`) — yet every slice project needs them as `provided` compile deps, so
nothing that touches persistence, HTTP, or notifications builds from Central alone. Wish: publish
the resource modules (drop the skip), or ship the resource *annotations/API* in an artifact that is
published.

---

*Built from the ticketing posterchild, 2026-06. Each item has a live reproduction in this repo; ask for the
exact files/symptoms per item. The two I'd file first: #1 (error→HTTP totality) and #2 (pub-sub honesty) —
both small, high-leverage, and silently wrong today.*
