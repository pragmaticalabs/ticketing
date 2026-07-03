namespace Ticketing.Shared

open Pragmatica

[<RequireQualifiedAccess>]
type PriceTierError =
    | Unknown of raw: string

    interface Cause with
        member this.Message =
            match this with
            | PriceTierError.Unknown raw -> $"Unknown price tier: {raw}"

[<RequireQualifiedAccess>]
type PriceTier =
    | Premium
    | Standard
    | Economy
    | Accessible
    | RestrictedView

[<RequireQualifiedAccess>]
module PriceTier =
    let parse (raw: string) : Result<PriceTier, Cause> =
        match raw.Trim().ToUpperInvariant() with
        | "PREMIUM" -> Ok PriceTier.Premium
        | "STANDARD" -> Ok PriceTier.Standard
        | "ECONOMY" -> Ok PriceTier.Economy
        | "ACCESSIBLE" -> Ok PriceTier.Accessible
        | "RESTRICTED_VIEW" -> Ok PriceTier.RestrictedView
        | _ -> Error(PriceTierError.Unknown raw :> Cause)

    /// Persistence/wire form: the Java enum constant name (`RESTRICTED_VIEW`), matching the `tier`
    /// column literals.
    let name (tier: PriceTier) =
        match tier with
        | PriceTier.Premium -> "PREMIUM"
        | PriceTier.Standard -> "STANDARD"
        | PriceTier.Economy -> "ECONOMY"
        | PriceTier.Accessible -> "ACCESSIBLE"
        | PriceTier.RestrictedView -> "RESTRICTED_VIEW"
