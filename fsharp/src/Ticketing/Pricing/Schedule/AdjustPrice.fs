namespace Ticketing.Pricing.Schedule

open System
open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.Shared.Event
open Ticketing.Pricing

/// Use case: scale the current price for an (event, tier) by a demand percentage (110 = +10%).
/// Telescope leaf -- system `ticketing` -> subsystem `pricing` -> workflow `schedule` -> use case
/// `adjust-price`. Recovery class: design-out -- the scaled price is a new appended row at a higher
/// version, never an overwrite. One use case, one `Request`/`Response` pair, one `execute` function.
[<Slice>]
module AdjustPrice =
    type Request =
        { Event: string
          Tier: string
          Percent: int64 }

    type Response = { Version: int64 }

    type AdjustError =
        | PriceNotFound
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | PriceNotFound -> "No price is available for this event and tier"
                | StoreUnavailable -> "Pricing store is unavailable"

    /// Validated demand-adjust request; the percentage is parsed into a positive `Percent` value
    /// object so a non-positive scale (which could zero or invert the price) is rejected up front.
    type ValidAdjust =
        { Event: EventId
          Tier: PriceTier
          Percent: Percent }

        member this.ScopeKey = $"{EventId.value this.Event}:{PriceTier.name this.Tier}"

    module ValidAdjust =
        let parse (request: Request) : Result<ValidAdjust, Cause> =
            result {
                let! event = EventId.parse request.Event
                and! tier = PriceTier.parse request.Tier
                and! percent = Percent.parse request.Percent
                return { Event = event; Tier = tier; Percent = percent }
            }

    /// Write target derived after reading and scaling the current price; reused by the shared commit
    /// path so the demand scaling reuses `Money.scaledByPercent` rather than duplicating its rounding.
    type ValidWrite =
        { Event: EventId
          Tier: PriceTier
          Price: Money }

        member this.EventGuid = EventId.value this.Event
        member this.TierName = PriceTier.name this.Tier
        member this.AmountMinor = Money.amountMinor this.Price
        member this.CurrencyName = Currency.name (Money.currency this.Price)
        member this.ScopeKey = $"{this.EventGuid}:{this.TierName}"

        // Tier-level pricing, so the fact carries an empty seat id.
        member this.Fact(version: int64) : PriceChanged =
            { EventId = string this.EventGuid
              SeatId = ""
              Tier = this.TierName
              AmountMinor = this.AmountMinor
              Currency = this.CurrencyName
              Version = version }

    type Execute = Request -> Promise<Response>

    let adjustPrice (store: PricingStore) (publisher: PriceChangedPublisher) : Execute =
        // Reconstruct the value object from the projection row using Money's validated from-minor
        // constructor and a revalidated Currency, so the demand scaling reuses Money's rounding.
        let toMoney (row: PriceRow) : Promise<Money> =
            Currency.parse row.Currency
            |> Result.bind (Money.fromMinor row.AmountMinor)
            |> Promise.fromResult

        let readCurrentPrice (scopeKey: string) : Promise<Money> =
            store.FindCurrent scopeKey
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (Promise.require PriceNotFound)
            |> Promise.bind toMoney

        // JBCT pattern: Sequencer -- upsert the projection, publish the change, carry the version.
        let publishCommitted (write: ValidWrite) (version: int64) : Promise<int64> =
            store.UpsertCurrent(write.ScopeKey, write.EventGuid, write.TierName, write.AmountMinor,
                                write.CurrencyName, version)
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (fun () -> publisher (write.Fact version))
            |> Promise.map (fun () -> version)

        let commit (write: ValidWrite) : Promise<Response> =
            store.AppendPrice(Guid.NewGuid(), write.EventGuid, write.TierName, write.AmountMinor, write.CurrencyName)
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (publishCommitted write)
            |> Promise.map (fun version -> { Version = version })

        let adjust (valid: ValidAdjust) : Promise<Response> =
            readCurrentPrice valid.ScopeKey
            |> Promise.map (Money.scaledByPercent valid.Percent)
            |> Promise.bind (fun scaled -> commit { Event = valid.Event; Tier = valid.Tier; Price = scaled })

        // JBCT pattern: Sequencer -- validate -> read current -> scale -> commit.
        fun request ->
            ValidAdjust.parse request
            |> Promise.fromResult
            |> Promise.bind adjust
