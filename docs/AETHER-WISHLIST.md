# Aether Runtime — DX Wish List

**From:** the ticketing posterchild (`org.pragmatica.example.ticketing`), built on Pragmatica/Aether/JBCT `1.0.0-rc3`.
**To:** the Aether runtime/toolchain maintainers.
**Why this exists:** this repo's stated purpose is "test our own tool" — build the book's full event-ticketing
platform as real Aether slices and surface the friction a HelloWorld never hits. This is that friction,
prioritized, with concrete reproductions from the build so each item is actionable.

> Framing for the whole list: **the core slice contract is genuinely good** — `@Slice` interface + static
> factory + `Promise<T>` + nested `Request`/`Response` + sealed `Cause`, resources as factory params, codecs
> you never write, and compile-time `@Query` validation. Almost all friction is at the **edges** (codegen,
> config, routing, pub-sub), not the core. Fixing the edges would make this a best-in-class backend DX.

## Priority summary

**Status legend:** ✅ landed (or fixed) upstream and taken up here · 🟡 landed but only partly usable · ⬜ still open.
Statuses are as of **rc3** and are verified against this repo's code, not against a changelog. The
per-item sections below retain the original problem statements; where an item has landed, the section
says so.

| # | Item | Component | Priority | Status (rc3) | Why |
|---|------|-----------|----------|--------------|-----|
| 1 | Error→HTTP **totality check** at compile time | slice-processor + http-routing | **P0** | ✅ `strict = true` in all 19 routed slices | silent 500s |
| 2 | Pub-sub **Promise honesty** (don't discard subscriber result) | infra-pubsub | **P0** | 🟡 error strategies exist; delivery still at-most-once here | silent data loss |
| 3 | **Auto-discover migrations** (kill `migrations.list`) | pg-codegen | **P0** | ✅ manifest deleted, build green | silent skip |
| 4 | **VO↔column mapping** (use VOs in `@Query`, not raw types) | pg-codegen | **P1** | ✅ shipped as `ValueMapping<T,P>`; adopted for `SeatState`/`PriceTier` | pervasive ceremony |
| 5 | `aether verify` — **whole-contract static check** | new mojo | **P1** | ⬜ | many late failures |
| 6 | **Typed** error→status (`@HttpStatus`), not glob-on-simple-names | routing | **P1** | ⬜ | fragile, drifts |
| 7 | **Scaffold/validate** the 4 TOMLs from annotations | maven plugin | **P1** | ⬜ | config sprawl |
| 8 | Support **text-block `@Query`** | pg-codegen | **P1** | ✅ used throughout | forced concatenation |
| 9 | Forge archive must **bundle resource providers** + fail fast | forge | **P1** | ⬜ live E2E still blocked | can't run live |
| 10 | Ship **`@Scheduled`/`@Heartbeat`** | runtime | **P1** | ✅ `Scheduled` adopted for `SweepHolds`; no `@Heartbeat` | workaround-forcing |
| 11 | **Shape-aware lint** (exempt transport/row/fact records) | jbct-lint | **P1** | 🟡 VO-01 bulk gone; 33 sites / 53 tokens remain | ~76 false suppressions |
| 12 | **Canonical-name codegen** + adversarial fixtures | slice-processor | **P2** | ✅ all 3 fixed — 2 in rc2, the 3rd (#15) on rc3 | (3 bugs, all fixed) |
| 13 | Clear errors: **multi-param method**, **15-dep cap**, **data-modifying CTE** | several | **P2** | 🟡 2 of 3; the cap is *worked around*, not gone | cryptic crashes |
| 14 | Tangential ideas (typed topics, observability aspect, test kit, …) | various | **P2** | 🟡 typed `Topic<T>` ✅, idempotency interceptor ✅ | future polish |
| **15** | **Hyphen in an interceptor config generates uncompilable code** | slice-processor | **P0** | ✅ **fixed upstream** (`311a1b0d7`, #561); workaround removed here | was build-breaking |
| **16** | **Interceptors attach only to slice methods** — no aspect for an internal call | resource-interceptors | **P1** | ⬜ **new on rc3** | retry/CB unusable |
| **17** | **Retry and metrics interceptors are unprovisionable from TOML** | resource-interceptors | **P1** | ⬜ **new on rc3** | declared, unusable |
| **18** | **`JBCT-ORD-01` is unsatisfiable for a slice impl record** — the rule and the slice shape disagree | jbct-lint | **P1** | ⬜ **new** | 24 forced suppressions |

---

## P0 — silent-failure / correctness traps

### 1. Compile-time error→HTTP **totality** check — ✅ LANDED in rc2, adopted here
**Status.** `ErrorMappingValidator` ships; `[errors] strict = true` turns an unmapped `Cause` into a
build failure (it only warns by default). **Enabled in all 19 routed slices here.** The problem
statement below is kept because the *default* is still permissive, and because the glob-on-simple-name
matching it validates is still the fragile part (#6).
**Problem.** A slice's failure set is a *closed* sealed `Cause` hierarchy, but its HTTP mapping lives in a
separate `routes.toml` matched by **globs over simple names** (`HTTP_400 = ["*Blank*"]`). Nothing forces the
two to agree, so a new error type silently falls through to the `default` (500), and a stale pattern silently
matches nothing.
**Evidence (this session).** Adding `CreateEventError.MalformedOnSaleAt` and `Percent.NonPositive` compiled
green and would have returned **500 instead of 400** — caught only by a manual doc/code reconciliation audit,
not the compiler. Separately, `setprice/routes.toml` carried a dead `HTTP_404 = ["*PriceNotFound*"]` matching
zero causes (SetPrice only declared `StoreUnavailable`). *Both cause names in that second example are the
pre-enum-grouping ones in force at the time; today SetPrice declares `ServiceUnavailable.PRICING_STORE`, and
the dead `HTTP_404` line is gone from that file. The trap it demonstrates — a stale pattern matching nothing —
is unchanged.*
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

### 3. **Auto-discover** migrations — ✅ LANDED in rc2, adopted here
**Status.** `SchemaLoader` globs `V*__*.sql` from the schema directory. **`migrations.list` is
deleted from this repo and the build is green without it** — the acceptance criterion below is met.
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

### 4. VO↔column mapping — use value objects in `@Query`, not raw types — ✅ LANDED in rc2 as `ValueMapping<T,P>`
**Status.** Shipped as `ValueMapping<T,P>` (absorbed this proposal, #397): the VO declares
`static ValueMapping<Vo,P> valueMapping()`, which drives row decode, `:param` binding and HTTP
path/query binding. **Adopted here for `SeatState` and `PriceTier`** at the store boundaries. The
original problem statement and the design rationale in §A are kept for the record.
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

### 10. Ship `@Scheduled` / `@Heartbeat` — ✅ `Scheduled` LANDED in rc2, adopted here
**Status.** `Scheduled` is real — a zero-parameter `Promise<Unit>` method, interval or cron,
KV-tracked — and **`SweepHolds` now uses it** (`sweep()` behind a custom `@SweepSchedule` qualifier,
60s). The operator HTTP route is kept alongside so a sweep can still be forced by hand. There is
still no `@Heartbeat`. Adoption caveat worth generalizing: `@ResourceQualifier` is
`@Target(ANNOTATION_TYPE)` only, so *every* `Scheduled` method needs a hand-written wrapper
annotation — the same ceremony the pub-sub qualifiers need.
**Was.** rc1/rc2 had no scheduled-trigger annotation, so periodic work (a hold-expiry sweep) had to be
modeled as an HTTP endpoint — which *misrepresented the design* (an Iteration/cron concern, not an API).

### 11. Shape-aware lint (stop false-positiving on framework shapes)
**Problem.** `JBCT-VO-01` ("record needs a factory") fires on records that legitimately have none — slice
`Request`/`Response`, `@PgSql` row records, `shared.event` facts, growing-context stage records. `JBCT-SEQ-01`
("chain > 5 steps") lands on the local **impl-record declaration** and sums every chained call across the whole
record body (a measurement artifact).
**Evidence (this session).** ~**76** suppressions were needed to reach 0 warnings — 59 VO-01 (all transport/row/
fact/DTO records) + 17 SEQ-01 (impl-record decls). Zero were real gaps.
**Progress (rc2).** VO-01 now exempts `@Slice`/`@PgSql` framework shapes, and **53 of the redundant
VO-01 suppressions were removed.** The VO-01 line is down to 3 (the `shared.event` fact records).
**Where it stands now.** `src/main/java` carries **33 suppression sites / 53 rule tokens**: 24
`JBCT-ORD-01`, 20 `JBCT-SEQ-01`, 6 `JBCT-UC-02`, 3 `JBCT-VO-01` (20 sites carry SEQ-01 and ORD-01
together). The SEQ-01 block is the measurement artifact above, untouched. The UC-02 block is the six
slices whose trigger is a fact or a timer rather than a `Request`. The ORD-01 block is a separate,
harder problem — see **#18**, which must be resolved before the acceptance below is reachable.
**Proposal.** Teach the linter the framework's shapes: auto-exempt `@Slice`-nested `Request`/`Response`,
`@PgSql` row records, and `@ResourceQualifier` fact records from VO-01; make SEQ-01 count a single method's
chain, not a record body. Net: those ~76 suppressions disappear.
**Acceptance.** The ticketing repo passes `jbct check` with **0 warnings and 0 suppressions**.

### 18. `JBCT-ORD-01` is unsatisfiable for a slice implementation record
**Problem.** ORD-01 orders type members by kind, ranking a nested `record` (0) ahead of a `static`
factory (3). The Aether slice contract puts the implementation record **inside** the body of its own
static factory method — so the record is lexically contained by the very declaration ORD-01 wants it
to precede. No reordering of members can satisfy the rule; only abandoning the slice shape can. This
is a genuine disagreement between a JBCT lint rule and the runtime's mandated shape, not a defect in
either the rule's intent or the code: **one of the two has to give, and it should not be the shape**,
because the nesting is what keeps the implementation private to its factory.
**Evidence (this repo).** All **24** slices suppress it at the impl-record declaration, each with the
reason on the line above; 20 of those sites carry `JBCT-SEQ-01` in the same annotation. That is a
100 % false-positive rate for the rule on this codebase — the failure mode is not the warning, it is
that a rule which is always wrong teaches people to suppress without reading.
**Proposal.** Exempt a record declared inside a method body from ORD-01 altogether. Member ordering is
a *type-body* concern; a method-local record has no sibling members to be ordered against, so the rule
has nothing meaningful to say about it. Narrower alternative if that is too broad: exempt a
method-local record whose name matches its enclosing method (the slice factory idiom exactly).
**Acceptance.** A slice written exactly as `CreateEvent` is — impl record nested in its factory —
passes `jbct check` with no ORD-01 warning and no suppression.

---

## P2 — robustness, clarity, and the long tail

### 12. Canonical-name codegen + adversarial fixtures *(all 3 fixed — the 3rd on rc3, see #15)*
Both codegen bugs found this session were the same root cause: emitting *simple* type names where Java shadows
them. **(a)** A factory's inner record `implements BuyTicket` shadowed the injected `QuotePrice.Request`/
`Response` (JLS §6.5.5.2). **(b)** Duplicate single-type imports for two error types sharing a simple name. Both
fixed via FQN emission (PR pragmaticalabs/pragmatica#364). A third, #15, surfaced on rc3 with the same
shape one level down — a *derived identifier* rather than a type reference — and is now fixed too
(`311a1b0d7`). **Lesson:** default generated code to fully-qualified
references *everywhere*, and add permanent fixtures for the two situations the book's idioms *guarantee* —
"slice injects slice, both with `Request`/`Response`" and "two errors, same simple name." Also: when generated
code fails to compile, the error lands far from the cause — **attribute generated-code errors back to the slice
+ source line.** That last ask is still open: #15's fix makes the bad identifier unreachable, but a
future generated-code failure would still surface as raw javac output with no slice attribution.

### 13. Replace cryptic crashes with clear errors
- **Multi-param slice method** crashes route generation (`parameterType()` assumes one). Either support multi-
  param (bind to path/body) or emit "a slice method takes exactly one request record." *(The one-param rule is
  arguably good design — it forced clean Request records — but it should be enforced with a message, not a crash.)*
- **15 transitive-dep cap** surfaced as `Too many dependencies (N) for Promise.all()`. **Partly
  addressed:** rc2 added `BatchedAll` in the slice-processor's *generator*, which chunks a generated
  factory's dependency list (fail-fast preserved). **The cap itself is not gone** — core's
  `Promise.all` still tops out at `Mapper15`, so hand-written code hits the same wall with the same
  message. Worth stating precisely in the docs, because "the cap is gone" is the natural shorthand
  and it is wrong. *(The cap is what made interface-segregation/synchronous-read tradeoffs legible —
  a constraint that shaped architecture. Keep the constraint, fix the message.)*
- **Data-modifying CTEs** mis-validate silently in pg-codegen — make it a clear "unsupported" error.

### 14. Tangential / speculative (explicitly low-probability, as requested)
- **First-class typed topics.** `Topic<SeatSold>` instead of a bare kebab-string `config="seat-sold"` (plus the
  historical `messaging.`-prefix confusion) → publisher/subscriber type-safe by construction, topic strings in
  one place. **✅ SHIPPED and adopted** — `Topic.of("seat-sold", SeatSold.class)` constants live on each
  fact record. Residual: the blueprint generator still validates one `resources.toml` section per
  *resolved* topic name, so the kebab-case sections must stay.
- **One descriptor, many boundaries.** Generalize the `PgRepr` of #4 to a neutral `Repr<T,P>` that *also* drives
  HTTP path/body binding and fact codecs — so `Request(SeatId seat)` auto-lifts the path segment (parse failure
  → typed 400) from the *same* one-line declaration. Kills "raw types at every boundary" holistically.
- **Idempotency/dedup as a subscriber resource** (since convergence/projection is *the* pattern).
  **✅ SHIPPED** — `IdempotencyInterceptorFactory` / `IdempotencyMethodInterceptor` /
  `IdempotencyConfig` are in `resource-interceptors`. Not adopted here only because the projections
  are already monotonic by per-seat version guard, which makes dedup redundant for this shape.
- **Observability as a slice-boundary aspect.** The slice boundary is a natural trace span; a built-in per-slice
  tracing/metrics aspect would deliver the "uniform observability (Aspects)" the design already promises.
  **Partly shipped** — `LoggingMethodInterceptor` and `MetricsMethodInterceptor` exist; logging is
  adopted here on the 5 fact consumers, metrics is not adoptable from TOML (#17).
- **Slice test kit.** Spin a slice with fakes/testcontainers + a typed client, so end-to-end slice tests don't
  need the full forge.
- **Schema-derived row types.** Derive `@PgSql` row records from the schema (or diff field↔column names with a
  clear message) — the row field must equal the column name, a coupling the `status→state` rename just exercised.
- **Naming-collision lint.** The `SeatStatus` slice vs `SeatState` enum homonym forced `import static` gymnastics;
  a warning would help (latent in any slice-per-use-case + shared-VO codebase).
- **Rename "Aether Store."** The term means `@PgSql` persistence — *not* a KV store, *not* the consensus KV —
  and `CLAUDE.md` literally has to warn about it. A clearer name removes a standing conceptual tripwire.
- **Local patched-jar fragility — resolved for the processor, replaced by a bigger one.** The build no
  longer depends on a hand-patched `slice-processor`: the official rc2 carries both PR #364 fixes.
  But the project now pins **rc3, which is not published at all**, so the *entire* dependency line is
  a local `mvn install` — a strictly larger version of the same fragility. See the release-completeness
  addendum.

---

## New on rc3 — found while adopting interceptors

### 15. A hyphen in an interceptor config generates uncompilable code — **P0** — ✅ FIXED upstream
**Status.** Fixed in `311a1b0d7` (#561) on `release-1.0.0-rc3`. **The workaround in this repo is
removed** and all eight interceptor sections are hyphenated again. The problem statement is kept
below because the reproduction and the lesson outlive the fix.
**Problem.** An interceptor `@ResourceQualifier(config = "cache.availability.seat-status")` made the
slice-processor emit an **illegal Java identifier**. `FactoryClassGenerator.collectUniqueInterceptors`
built the lambda parameter name using `configSection().replace('.', '_')` as its *only* sanitization,
while the section itself is arbitrary user text — so the emitted factory read
`.map((store, methodInterceptor_cache_availability_seat-status) -> {` and javac reported
`')' or ',' expected` / `illegal start of expression` **inside generated code**, with no `[SLICE-…]`
diagnostic pointing at the slice. The type half of the same name already went through
`variableSafeName()`; only the config half was unguarded.
**Evidence (this session).** Hit on the first interceptor added. The bug was specific to *interceptor*
configs — `[scheduling.sweep-holds]` and the kebab-case topic sections (`[seat-sold]`) compiled
normally, because `Scheduled` never reaches the factory and publisher/subscription qualifiers pass the
hyphenated topic through as a *string literal*. That is exactly why it went unnoticed until
interceptors were adopted, and why it was easy to hit: hyphens are the established house style for
every *other* config section. The workaround at the time was to spell every interceptor section with
underscores (`cache.availability.seat_status`), which made `resources.toml` internally inconsistent.
**Resolution.** Both halves of the proposal landed, in one commit. Sanitization moved into
`ResourceQualifierModel.variableSafeConfigSection()`, replacing every code point that fails
`Character.isJavaIdentifierPart` with `_` (identifier-*part*, not -*start*: the fragment is only ever
appended after a `typeName_` prefix, so a leading digit is legal there). Sanitizing alone was not
sufficient — it is not injective, so `a-b` and `a_b` collapse onto one identifier while
`deduplicationKey()` still keeps them as two entries, and two interceptors differing only by that
separator declared the same lambda parameter twice. Issued names are therefore tracked and
de-collided with a numeric suffix (`…_2`). No annotation-site rejection was added, which is the
better outcome: every input now yields a valid unique identifier, and rejecting hyphens would have
broken the very TOML style this item asked to support. Only the local variable name changed — the
`ctx.resources().provide(Type.class, "…")` literal still carries the section verbatim, so resolution
behaviour and envelope structure are untouched. Three regression tests cover the hyphenated section,
the separator collision, and the pre-existing dotted form; the interceptor path had **no fixture at
all** before, which is why it shipped.
**Lesson (unchanged, and the same as #12).** Generated identifiers and references need a canonical,
*total* transformation — plus fixtures for the inputs house style guarantees. This was the third
codegen bug this project found in the rc series; all three are now fixed upstream.

### 16. Interceptors attach only to slice methods — so retry/circuit-breaking is unusable where it is needed — **P1**
**Problem.** A `MethodInterceptor` attaches to a `@Slice` interface method. Real resilience concerns
attach to a single *outbound call*, which is almost never a whole slice method.
**Evidence (this session).** The obvious candidate — retry + circuit-breaker around the `@Http`
payment gateway — is **not adoptable**. The gateway calls are private helpers inside
`BuyTicket`/`CancelTicket`, so the only attachable method is `execute`: the entire BER saga.
Retrying it would re-run seat claiming and confirmation, not the payment call.
**Worse, the breaker would trip on the designed outcome.** `CircuitBreakerInterceptorFactory` builds
its breaker with `.withDefaultShouldTrip()`, and core's default is literally `shouldTrip(_ -> true)`
(`CircuitBreaker.java`), so **every** typed `Cause` counts toward the failure threshold.
`CircuitBreakerConfig` carries only `failureThreshold`, `resetTimeout` and `testAttempts` — there is
**no way to supply a predicate**. On a hot event, `StateConflict.SEAT_UNAVAILABLE` — the *intended* result of the
contended-seat design-out, not a fault — would count as failure and open the breaker, taking the buy
path down precisely when it is working as designed.
**Proposal.** Two things, independently useful: (a) let `CircuitBreakerConfig` carry a trip
predicate (or default to tripping only on a designated "infrastructure failure" marker interface
rather than on all `Cause`s); (b) offer an attachment point finer than a slice method — e.g.
intercepting a *resource* call (`@Http` client method) rather than only a slice method.
**Note on the structural fix.** In this codebase the right answer is to extract the gateway into its
own `PaymentGateway` slice, whose `execute` *is* the payment call. That is a good design change
regardless — but it should be a choice, not the only way to get a retry.
**Acceptance.** A circuit breaker can be configured to ignore domain causes; retry can wrap a single
outbound call without wrapping its caller's whole saga.

### 17. Retry and metrics interceptors cannot be provisioned from TOML — **P1**
**Problem.** Both are shipped and documented as interceptors, but their config records carry **Java
objects that a TOML section cannot express**:
- `RetryConfig(int maxAttempts, BackoffStrategy backoffStrategy)` — `BackoffStrategy` is a core
  builder type (`Retry.BackoffStrategy.exponential()/fixed()`), not a value that can be bound from
  config.
- `MetricsConfig(String name, MeterRegistry registry, …)` — requires an injected micrometer
  `MeterRegistry`.

By contrast `CacheConfig` and `LogConfig` are TOML-expressible, which is why those two are the only
interceptors this repo could actually adopt.
**Proposal.** Give each a config surface that is fully declarative — e.g.
`strategy = "exponential", initial = "100ms", max = "5s"` for retry — and resolve the registry from
the runtime rather than requiring the caller to hold one.
**Acceptance.** A retry or metrics interceptor can be declared entirely in `resources.toml`, like a
cache.

### Also evaluated on rc3 and deliberately deferred: declarative stream consumers
Migrating the pub-sub consumers to declarative stream consumers was assessed and **deferred**. Three
reasons, all from rc3's own sources/tests: cross-node failover is **explicitly disclaimed by the
rc3 test suite**; `@PartitionKey` is a **no-op for a topic `Publisher`**; and the cursor advance is an
**unconditional set**, so a failed handler still advances past its message. That last one reproduces
the exact shape of #2 — an API that reads as if it offers a delivery guarantee it does not keep. The
current at-most-once story is at least honest about itself.

---

## §A — VO↔column mapping, in detail (the design behind #4)

> **Status: shipped in rc2 as `ValueMapping<T,P>` and adopted here** for `SeatState`/`PriceTier` at
> the store boundaries — rows decode VOs via `RowDecodeError.guard(… .lift())`, so a corrupt column
> value now fails typed at the row boundary. The design discussion below is kept because it records
> *why* the shape is what it is.

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
in the released sources. **The "adopted here" column supersedes the earlier "not yet exercised"
notes** — the adoption pass happened the same day and is recorded in the paragraph below the table.

| # | Item | rc2 status | Adopted here |
|---|------|-----------|--------------|
| 1 | Error→HTTP totality | **Landed** — `ErrorMappingValidator` (totality + dead-pattern/reference); unmapped `Cause` fails the build only under `[errors] strict = true`, warns by default | **Yes** — `strict = true` in all 19 routed slices |
| 2 | Pub-sub honesty | **Partial** — per-consumer-group `ErrorStrategy{RETRY,SKIP,STALL}` + `dead-letter` stream + `max-retries` in stream config; runtime dispatch not verifiable from published sources | No — declarative stream consumers evaluated and deferred (see rc3 addendum) |
| 3 | Auto-discover migrations | **Landed** — `SchemaLoader` globs `V*__*.sql` from the directory; `migrations.list` now advisory | **Yes** — manifest deleted, build green without it |
| 4 | VO↔column mapping | **Landed** — `ValueMapping<T,P>` (absorbed the `PgRepr` proposal, #397): VO convention `static ValueMapping<Vo,P> valueMapping()`, drives row decode AND `:param` binding, plus HTTP path/query binding | **Yes** — `SeatState`/`PriceTier` at store boundaries |
| 5 | `aether verify` | **Can't tell** — no verify/validate surface found in the published cli pom | n/a |
| 6 | `@HttpStatus` | Not landed — error→status still routes.toml pattern-globs | n/a |
| 7 | Config scaffolding | Not landed — but typed `Topic<T>` (#396) removes much of the manual `resources.toml` topic wiring | Partly — topics typed, sections still required |
| 8 | Text-block `@Query` | **Landed** — verified empirically in this repo | **Yes** — used throughout |
| 9 | Forge bundles providers | Not landed — no forge submodule depends on `resource-http`/`resource-notification`; live E2E still blocked | n/a |
| 10 | `@Scheduled`/`@Heartbeat` | **Partial** — `Scheduled` is real (zero-param `Promise<Unit>` methods, interval or cron, KV-tracked state). No `@Heartbeat` | **Yes** — `SweepHolds.sweep()` on a 60s schedule; operator route kept |
| 11 | Shape-aware lint | **Partial** — JBCT-VO-01 now exempts `@Slice`/`@PgSql` framework shapes; no test-tree awareness | **Yes** — the VO-01 bulk (53) is gone; 33 sites / 53 tokens remain (24 ORD-01, 20 SEQ-01, 6 UC-02, 3 VO-01) |
| 12 | Canonical-name codegen | **Landed** — both PR #364 fixes confirmed in released sources | Yes — **and the third codegen bug (#15), found on rc3, is now fixed upstream too** |
| 13 | Clear errors | **Two of three landed** — multi-param slice methods auto-generate a wrapper `<Method>Request` (no more crash); data-modifying CTEs now a clear located compile error. **The 15-dep cap is NOT gone:** `BatchedAll` lives in the slice-processor's *generator* and chunks a generated factory's dependency list; core's `Promise.all` still tops out at `Mapper15`, so hand-written code is unchanged | n/a |
| 14 | Typed topics | **Landed** — `Topic<T>` in slice-api (envelope 1005→1006) | **Yes** — `Topic.of(...)` constants on each fact record |

Also new in rc2, not on the list: `RateGuard` (injectable backpressure resource +
`ResourceCapacityExhausted`), aspect-level observability config surface, `DeferredSliceInvokerFacade`,
stream `TierAwareRetention`, header-mode API versioning (#198), `produces`/`consumes` media types (#339).
**The idempotency/dedup subscriber resource requested in #14 also shipped** —
`IdempotencyInterceptorFactory` / `IdempotencyMethodInterceptor` / `IdempotencyConfig` are in
`resource-interceptors`. Not adopted here: the projections are already monotonic by version guard
(§5 of `DESIGN.md`), so deduplication would be redundant.

**Adoption findings (this repo, 2026-07-17):** (a) `@ResourceQualifier` is `@Target(ANNOTATION_TYPE)`
only — a `Scheduled` method needs a custom qualifier annotation (we added `@SweepSchedule`), same
pattern as the subscription qualifiers. (b) Typed `Topic<T>` constants resolve fine in the
slice-processor, but `jbct-maven-plugin generate-blueprint` still validates one `resources.toml`
section per *resolved* topic name — the kebab-case sections must stay alongside the constants.
Wish: teach blueprint validation the typed form so the redundant sections can go.

---

## Addendum — release completeness (rc2, and where rc3 stands)

`1.0.0-rc2` reached Maven Central on 2026-07-16, but the **`aether/resource` subtree was not
published — deliberately**: `aether/resource/pom.xml` at `v1.0.0-rc2` sets
`<skipPublishing>true</skipPublishing>` (central-publishing-maven-plugin), inherited by all 13
resource modules. Result: `resource-api` and `resource-notification` 404, and no published rc2 jar
contains `org.pragmatica.aether.resource.*` (`@PgSql`, `@Http`/`HttpClient`,
`@Notify`/`NotificationSender`) — yet every slice project needs them as `provided` compile deps, so
nothing that touches persistence, HTTP, or notifications builds from Central alone.

**On rc3 this got wider, then moot.** Adopting interceptors added a **third** unpublished
dependency, `resource-interceptors`, from the same skipped subtree. And `1.0.0-rc3` is **not on
Maven Central at all** — Central's newest published line is still rc2, so this project now builds
entirely against a local `mvn install` of `release-1.0.0-rc3`. Verified mechanically: every rc3
artifact in `~/.m2` carries no repository marker, while e.g. `slice-api:1.0.0-rc2` carries
`central=`.

**Wish (unchanged, now with a third artifact behind it):** publish the resource modules — drop the
skip — or ship the resource *annotations/API* in an artifact that is published. As it stands, no
third party can build any non-trivial slice project from Central, on any rc line.

---

*Built from the ticketing posterchild; items 1–14 from the 2026-06 build, items 15–17 from the rc3
interceptor adoption. Each item has a live reproduction in this repo; ask for the exact
files/symptoms per item. **#15 has since been fixed upstream** (`311a1b0d7`, #561) and its workaround
is removed here. Of what remains, the three I'd file first: **#1** (error→HTTP totality), **#2**
(pub-sub honesty) and **#16** (circuit breaker trips on designed outcomes — a payment-gateway breaker
is still unsafe to adopt).*
