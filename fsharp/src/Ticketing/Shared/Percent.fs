namespace Ticketing.Shared

open Pragmatica

[<RequireQualifiedAccess>]
type PercentError =
    | NonPositive of value: int64

    interface Cause with
        member this.Message =
            match this with
            | PercentError.NonPositive value -> $"Percent must be positive: {value}"

/// Demand-scaling percentage (110 = +10%, 90 = -10%). Strictly positive by construction: scaling a
/// non-negative price by a positive percentage can never drive it to zero or below, so `Money`
/// stays non-negative without a further check. Parse-don't-validate: the raw request `int64` is
/// admitted only through `Percent.parse`.
type Percent = private Percent of int64

[<RequireQualifiedAccess>]
module Percent =
    let parse (value: int64) : Result<Percent, Cause> =
        value
        |> Verify.ensure Verify.Is.positive (PercentError.NonPositive value)
        |> Result.map Percent

    let value (Percent value) = value
