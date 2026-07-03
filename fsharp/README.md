# The Ticketing Platform, Rendered in F#

This directory is a **faithful F# rendition of the Java codebase in `../src`** — same PFD design,
same 23 single-use-case slices, same subsystems (`booking`, `pricing`, `eventmanagement`,
`availability`, `quote`), same SQL, same error messages, same recovery classes (design-out / BER /
FER). Its purpose is to show what the JBCT/Aether idioms *look like* when the language itself
provides the things the Java patterns work to establish.

It is **not** a port of the Aether runtime. `src/Pragmatica.fs` and `src/Aether.fs` are thin
stand-ins (~230 lines) that carry the same names as the real contracts (`Cause`, `Promise`,
`@Slice`, `@PgSql`, `@Query`, `Publisher`, `HttpClient`, `NotificationSender`) so every slice reads
one-to-one against its Java counterpart. There is no annotation processor, no codec generation, no
deployment — the point is the shape of the business code, which lands at ~2,200 lines of F# for
~3,600 lines of Java while keeping every step, comment-worthy constraint, and failure case.

```bash
dotnet build fsharp/Ticketing.fsproj     # .NET 10 SDK; builds clean with warnings-as-errors
```

## How each Java idiom maps

| Java (JBCT / Aether) | F# | Why it gets shorter |
|---|---|---|
| `sealed interface FooError extends Cause` + one record per case + one static factory per case | one discriminated union implementing `Cause` | the DU *is* the closed set; cases need no bodies, no factories |
| `@Slice` interface with a single `execute` + local `record ... implements` + factory returning it | module with `type Execute = Request -> Promise<Response>`; the factory returns the function | a one-method interface is a function type; the implementing record disappears |
| named private step methods on the impl record | named `let` functions inside the factory closure | same Sequencer discipline, no `this`, no record plumbing |
| `Result.all(a, b, c).map(Ctor::new)` | `result { let! a = ... and! b = ... and! c = ... return ... }` | `and!` is applicative — failed components still surface together as one composite cause |
| `Verify.ensure(x, Verify.Is::present, err)` | `x \|> Verify.ensure Verify.Is.present Err` | direct equivalent |
| `record SeatId(Uuid value)` × 8 near-identical files | 8 one-line single-case unions + **one** shared `Id.parse` | the clones collapse; the types stay distinct (a `SeatId` still can't stand in for an `EventId`) |
| `Money` invariant held by auditing every construction boundary (the canonical constructor is unavoidably public) | `type Money = private { ... }` | the constructor is *actually* private; `parse`/`fromMinor` returning `Result` are the only ways in — the doc comment's lament becomes a language feature |
| enums with `valueOf` + `dbValue()` | unions with `parse` / `dbValue` functions | pattern matching makes the total mapping visible |
| `@PgSql` interface + `@Query` SQL, plain row records | F# interface + `[<Query>]` attribute, F# records | unchanged on purpose — the SQL is the design |
| `Promise.all(a, b).flatMap(...)` Fork-Join | `Promise.all2 a b \|> Promise.bind ...` | direct equivalent (parallel start, joined tuple) |
| `promise.fold(result -> compensate(...))` BER hook | `Promise.onResult` + `match result with Ok ... \| Error cause ->` | the compensation routing is a visible two-case match |
| growing-context records with delegating accessors | records with members (`member this.Buy = this.Priced.Buy`) | same growing-context style |
| `@SeatSoldPublisher Publisher<SeatSold>` qualifier annotations | `type SeatSoldPublisher = Publisher<SeatSold>` (a named function type) | the qualifier reduces to a type alias; a consumer slice's `Execute` *is* the subscription signature |

## One slice, side by side

The closed failure set of `OpenEvent` — Java (~34 lines):

```java
sealed interface OpenEventError extends Cause {
    record EventNotFound() implements OpenEventError {
        @Override
        public String message() { return "Event not found"; }
    }
    record AlreadyOpen() implements OpenEventError { /* ... */ }
    record StoreUnavailable() implements OpenEventError { /* ... */ }

    static OpenEventError eventNotFound() { return new EventNotFound(); }
    static OpenEventError alreadyOpen() { return new AlreadyOpen(); }
    static OpenEventError storeUnavailable() { return new StoreUnavailable(); }
}
```

F# (11 lines):

```fsharp
type OpenEventError =
    | EventNotFound
    | AlreadyOpen
    | StoreUnavailable

    interface Cause with
        member this.Message =
            match this with
            | EventNotFound -> "Event not found"
            | AlreadyOpen -> "Event is already open for sale"
            | StoreUnavailable -> "Event management store is unavailable"
```

And the slice contract itself: the Java `@Slice` interface + factory + local implementing record
becomes a module whose factory closes over its resources and returns the `execute` function:

```fsharp
[<Slice>]
module OpenEvent =
    type Execute = Request -> Promise<Response>

    let openEvent (store: EventStore) : Execute =
        let doOpen (eventId: EventId) : Promise<Response> = ...

        // JBCT pattern: Sequencer -- validate -> ensure event exists -> guarded open.
        fun request ->
            EventId.parse request.Event
            |> Promise.fromResult
            |> Promise.bind ensureEventThenOpen
```

Cross-subsystem synchronous reads keep the same wiring: `BuyTicket`'s factory takes
`quotePrice: QuotePrice.Execute` and `saleStatus: SaleStatus.Execute` where the Java factory takes
the `QuotePrice` and `SaleStatus` slice interfaces.

## What F# adds beyond compression

- **Compile order is the telescope.** `Ticketing.fsproj` lists files in dependency order — shared
  vocabulary → write subsystems (`eventmanagement` → `pricing` → `booking`) → read subsystems. A
  slice physically cannot reference a slice that compiles after it, so the design's dependency
  direction (booking reads pricing and event management; reads hang off facts) is enforced by the
  compiler instead of by review.
- **Steps read definition-first.** F# requires definition before use, so inside a factory the leaf
  steps appear first and the Sequencer chain last — the inverse of Java's top-down reading, and the
  chain at the bottom is the whole use case in six lines.
- **Total matches.** Every `parse`/`dbValue`/`Message` is a `match` the compiler checks for
  exhaustiveness; adding a failure case breaks the build until every consumer handles it.
- **`CheckHold`'s decay classification** (three chained Java condition methods) is one four-case
  pattern match.

## What is deliberately unchanged

- **The SQL.** Every `[<Query>]` string is byte-identical to its Java `@Query` (including the
  design-out `ON CONFLICT ... WHERE` seat claim and the atomic version allocation). The store
  interfaces model the same pg-codegen contract: `Promise<Row option>` for one-or-none,
  `Promise<Row list>` for many, `Promise<unit>` for writes.
- **Error messages, route-relevant case names, and recovery choreography** — `routes.toml` error
  mapping by simple name would work unchanged, and the BER saga in `BuyTicket` compensates in
  exactly the same order (including writing the `bookings` row last).
- **`schema/`, `routes.toml`, blueprints, deployment** — language-neutral, owned by the Java
  project; nothing here duplicates them.

## Verification

The Java test suite is not ported (it belongs to the reference implementation), but the rendition
is behavior-checked end-to-end: `dotnet build` is clean under warnings-as-errors, and `Smoke.fsx`
(`cd fsharp && dotnet fsi Smoke.fsx`, after building) drives the compiled assembly through the
load-bearing semantics — applicative validation
accumulating all four `ValidBuy` causes, `Money` rejecting negatives/sub-cent amounts and rounding
`scaledByPercent` HALF_UP, and the full `BuyTicket` saga against a scripted store: happy path
publishes `SeatSold` and writes `tickets → payments → bookings` in that order; a declined payment
releases the reservation and re-raises `PaymentDeclined`; a lost confirm race voids + releases and
surfaces `SeatUnavailable`; `CheckHold` maps decay to `NONE/EXPIRED/STALE/FRESH`.
