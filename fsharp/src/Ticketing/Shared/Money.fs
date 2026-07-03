namespace Ticketing.Shared

open System
open System.Globalization
open Pragmatica

[<RequireQualifiedAccess>]
type Currency =
    | USD
    | EUR
    | GBP

[<RequireQualifiedAccess>]
type MoneyError =
    | MalformedAmount of raw: string
    | NegativeAmount of amountMinor: int64
    | UnknownCurrency of raw: string
    | CurrencyMismatch of left: Currency * right: Currency

    interface Cause with
        member this.Message =
            match this with
            | MoneyError.MalformedAmount raw -> $"Amount is malformed: {raw}"
            | MoneyError.NegativeAmount amountMinor -> $"Amount must not be negative: {amountMinor}"
            | MoneyError.UnknownCurrency raw -> $"Unknown currency: {raw}"
            | MoneyError.CurrencyMismatch(left, right) -> $"Currency mismatch: {left} vs {right}"

[<RequireQualifiedAccess>]
module Currency =
    let parse (raw: string) : Result<Currency, Cause> =
        match raw.Trim().ToUpperInvariant() with
        | "USD" -> Ok Currency.USD
        | "EUR" -> Ok Currency.EUR
        | "GBP" -> Ok Currency.GBP
        | _ -> Error(MoneyError.UnknownCurrency raw :> Cause)

    let name (currency: Currency) = string currency

/// Non-negative monetary amount in minor units. The Java record's canonical constructor is
/// unavoidably public, so the invariant is held there by auditing every construction boundary; in
/// F# the constructor itself is private — no reachable path constructs a negative `Money`, and the
/// only ways in (`parse`, `fromMinor`) return a `Result`.
type Money =
    private
        { AmountMinor: int64
          Currency: Currency }

[<RequireQualifiedAccess>]
module Money =
    let amountMinor (money: Money) = money.AmountMinor

    let currency (money: Money) = money.Currency

    let fromMinor (amountMinor: int64) (currency: Currency) : Result<Money, Cause> =
        amountMinor
        |> Verify.ensure Verify.Is.nonNegative (MoneyError.NegativeAmount amountMinor)
        |> Result.map (fun minor -> { AmountMinor = minor; Currency = currency })

    let private minorUnits (amount: string) : Result<int64, Cause> =
        match Decimal.TryParse(amount, NumberStyles.Number, CultureInfo.InvariantCulture) with
        | false, _ -> Error(MoneyError.MalformedAmount amount :> Cause)
        | true, value ->
            let minor = value * 100m

            // Java's `movePointRight(2).longValueExact()`: sub-cent precision or an overflowing
            // amount is malformed, not silently rounded.
            if Decimal.Truncate minor <> minor
               || minor > decimal Int64.MaxValue
               || minor < decimal Int64.MinValue then
                Error(MoneyError.MalformedAmount amount :> Cause)
            elif minor < 0m then
                Error(MoneyError.NegativeAmount(int64 minor) :> Cause)
            else
                Ok(int64 minor)

    let parse (amount: string) (currency: string) : Result<Money, Cause> =
        result {
            let! minor = minorUnits amount
            and! parsedCurrency = Currency.parse currency
            return { AmountMinor = minor; Currency = parsedCurrency }
        }

    let plus (other: Money) (money: Money) : Result<Money, Cause> =
        if money.Currency = other.Currency then
            Ok { money with AmountMinor = money.AmountMinor + other.AmountMinor }
        else
            Error(MoneyError.CurrencyMismatch(money.Currency, other.Currency) :> Cause)

    /// A positive `Percent` applied to a non-negative amount stays non-negative, so the result needs
    /// no further check. This is the only way to scale a price: a raw int64 can no longer mint a
    /// negative `Money`.
    let scaledByPercent (percent: Percent) (money: Money) : Money =
        let scaled =
            decimal money.AmountMinor * decimal (Percent.value percent) / 100m

        { money with AmountMinor = int64 (Math.Round(scaled, 0, MidpointRounding.AwayFromZero)) }

    let render (money: Money) =
        let amount = (decimal money.AmountMinor / 100m).ToString("0.00", CultureInfo.InvariantCulture)
        $"{amount} {Currency.name money.Currency}"
