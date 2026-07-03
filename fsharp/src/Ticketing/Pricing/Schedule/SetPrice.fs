namespace Ticketing.Pricing.Schedule

open System
open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.Shared.Event
open Ticketing.Pricing

/// Use case: set an absolute price for an (event, tier).
/// Telescope leaf -- system `ticketing` -> subsystem `pricing` -> workflow `schedule` -> use case
/// `set-price`. Recovery class: design-out -- a correction is a new appended row at a higher
/// version, never an overwrite. One use case, one `Request`/`Response` pair, one `execute` function.
[<Slice>]
module SetPrice =
    type Request =
        { Event: string
          Tier: string
          Amount: string
          Currency: string }

    type Response = { Version: int64 }

    type PricingError =
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | StoreUnavailable -> "Pricing store is unavailable"

    /// Validated write target: the raw request fields are parsed into value objects; all failures
    /// surface together via `and!`.
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

    module ValidWrite =
        let parse (request: Request) : Result<ValidWrite, Cause> =
            result {
                let! event = EventId.parse request.Event
                and! tier = PriceTier.parse request.Tier
                and! price = Money.parse request.Amount request.Currency
                return { Event = event; Tier = tier; Price = price }
            }

    type Execute = Request -> Promise<Response>

    let setPrice (store: PricingStore) (publisher: PriceChangedPublisher) : Execute =
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

        // JBCT pattern: Sequencer -- validate -> commit (append@version -> upsert -> publish).
        fun request ->
            ValidWrite.parse request
            |> Promise.fromResult
            |> Promise.bind commit
