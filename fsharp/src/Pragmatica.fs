/// F# stand-in for `org.pragmatica.lang` — the JBCT core vocabulary (Cause, Result, Promise,
/// Verify). F#'s own `Result<'T, 'E>` and `Option<'T>` replace Pragmatica's; only the pieces with
/// no built-in analog are defined here.
namespace Pragmatica

open System
open System.Globalization

/// Typed failure — the closed, enumerable alternative to exceptions (Java: `org.pragmatica.lang.Cause`).
/// Each subsystem models its failures as a discriminated union implementing this interface, which
/// keeps the set sealed (the compiler knows every case) while still letting any failure flow through
/// the shared `Promise` plumbing.
type Cause =
    abstract Message: string

/// Several causes surfaced together (Java: the composite produced by `Result.all`).
type CompositeCause =
    { Causes: Cause list }

    interface Cause with
        member this.Message =
            this.Causes
            |> List.map (fun cause -> cause.Message)
            |> String.concat "; "

[<RequireQualifiedAccess>]
module Cause =
    let private flatten (cause: Cause) =
        match cause with
        | :? CompositeCause as composite -> composite.Causes
        | single -> [ single ]

    let combine (left: Cause) (right: Cause) : Cause =
        { Causes = flatten left @ flatten right } :> Cause

/// Async computation ending in success or a typed failure (Java: `org.pragmatica.lang.Promise<T>`).
type Promise<'T> = Async<Result<'T, Cause>>

[<RequireQualifiedAccess>]
module Result =
    /// Replace whatever cause failed with a fixed one (Java: `result.mapError(_ -> cause)`).
    let orFail (cause: #Cause) (result: Result<'T, Cause>) : Result<'T, Cause> =
        result |> Result.mapError (fun _ -> cause :> Cause)

[<RequireQualifiedAccess>]
module Promise =
    let success (value: 'T) : Promise<'T> = async { return Ok value }

    let fail (cause: #Cause) : Promise<'T> = async { return Error(cause :> Cause) }

    /// Lift a validated Result into the async world (Java: `result.async()`).
    let fromResult (result: Result<'T, Cause>) : Promise<'T> = async { return result }

    /// Admit a one-or-none projection, failing with the given cause when it is empty
    /// (Java: `option.async(cause)`).
    let require (cause: #Cause) (option: 'T option) : Promise<'T> =
        match option with
        | Some value -> success value
        | None -> fail cause

    let map (mapping: 'T -> 'U) (promise: Promise<'T>) : Promise<'U> =
        async {
            let! result = promise
            return Result.map mapping result
        }

    let bind (binder: 'T -> Promise<'U>) (promise: Promise<'T>) : Promise<'U> =
        async {
            match! promise with
            | Ok value -> return! binder value
            | Error cause -> return Error cause
        }

    let mapError (mapping: Cause -> Cause) (promise: Promise<'T>) : Promise<'T> =
        async {
            let! result = promise
            return Result.mapError mapping result
        }

    /// Replace whatever cause failed with a fixed one (Java: `promise.mapError(_ -> cause)`).
    let orFail (cause: #Cause) (promise: Promise<'T>) : Promise<'T> =
        mapError (fun _ -> cause :> Cause) promise

    /// Swallow a failure into a fallback value (Java: `promise.recover(...)`).
    let recover (fallback: Cause -> 'T) (promise: Promise<'T>) : Promise<'T> =
        async {
            match! promise with
            | Ok value -> return Ok value
            | Error cause -> return Ok(fallback cause)
        }

    /// Materialize the outcome — success or failure — so an Aspect can wrap it
    /// (Java: `promise.fold(result -> ...)`). This is where BER compensation hooks in.
    let onResult (handler: Result<'T, Cause> -> Promise<'U>) (promise: Promise<'T>) : Promise<'U> =
        async {
            let! result = promise
            return! handler result
        }

    /// Fork-Join over two independent steps: both run in parallel, the join sees the pair
    /// (Java: `Promise.all(a, b)`). The first failure wins.
    let all2 (left: Promise<'A>) (right: Promise<'B>) : Promise<'A * 'B> =
        async {
            let! leftChild = Async.StartChild left
            let! rightChild = Async.StartChild right
            let! leftResult = leftChild
            let! rightResult = rightChild

            match leftResult, rightResult with
            | Ok a, Ok b -> return Ok(a, b)
            | Error cause, _ -> return Error cause
            | _, Error cause -> return Error cause
        }

    /// Run a batch in parallel and collect every success (Java: `Promise.allOf(list)`).
    let allOf (promises: Promise<'T> list) : Promise<'T list> =
        async {
            let! results = promises |> Async.Parallel

            return
                (results, Ok [])
                ||> Array.foldBack (fun result acc ->
                    match result, acc with
                    | Ok value, Ok values -> Ok(value :: values)
                    | Error cause, _ -> Error cause
                    | _, Error cause -> Error cause)
        }

/// Parse-don't-validate guard (Java: `org.pragmatica.lang.Verify`).
[<RequireQualifiedAccess>]
module Verify =
    let ensure (predicate: 'T -> bool) (cause: #Cause) (value: 'T) : Result<'T, Cause> =
        if predicate value then Ok value else Error(cause :> Cause)

    [<RequireQualifiedAccess>]
    module Is =
        let present (value: string) = not (String.IsNullOrWhiteSpace value)

        let positive (value: int64) = value > 0L

        let nonNegative (value: int64) = value >= 0L

/// Applicative validation (Java: `Result.all(a, b, ...).map(Ctor::new)`): `let! ... and! ...`
/// evaluates every component and surfaces the failed ones together as one composite cause.
type ResultBuilder() =
    member _.Return(value: 'T) : Result<'T, Cause> = Ok value

    member _.ReturnFrom(result: Result<'T, Cause>) = result

    member _.Bind(result: Result<'T, Cause>, binder: 'T -> Result<'U, Cause>) = Result.bind binder result

    member _.BindReturn(result: Result<'T, Cause>, mapping: 'T -> 'U) = Result.map mapping result

    member _.MergeSources(left: Result<'A, Cause>, right: Result<'B, Cause>) : Result<'A * 'B, Cause> =
        match left, right with
        | Ok a, Ok b -> Ok(a, b)
        | Error leftCause, Error rightCause -> Error(Cause.combine leftCause rightCause)
        | Error cause, _
        | _, Error cause -> Error cause

[<AutoOpen>]
module ResultBuilderInstance =
    let result = ResultBuilder()

/// ISO-8601 timestamp value object (Java: `org.pragmatica.lang.vo.IsoDateTime`).
type IsoDateTime = private IsoDateTime of DateTimeOffset

[<RequireQualifiedAccess>]
module IsoDateTime =
    type private MalformedIsoDateTime =
        { Raw: string }

        interface Cause with
            member this.Message = $"Not a valid ISO-8601 timestamp: {this.Raw}"

    let parse (raw: string) : Result<IsoDateTime, Cause> =
        match DateTimeOffset.TryParse(raw, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind) with
        | true, value -> Ok(IsoDateTime value)
        | false, _ -> Error({ Raw = raw } :> Cause)

    let render (IsoDateTime value) = value.ToString("o", CultureInfo.InvariantCulture)
