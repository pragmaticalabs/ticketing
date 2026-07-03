namespace Ticketing.Pricing.Quoting

open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.Pricing

/// Use case: quote the authoritative current price for an (event, tier).
/// Telescope leaf -- system `ticketing` -> subsystem `pricing` -> workflow `quoting` -> use case
/// `quote-price`. One use case, one `Request`/`Response` pair, one `execute` function.
[<Slice>]
module QuotePrice =
    type Request = { Event: string; Tier: string }

    type Response =
        { Event: string
          Tier: string
          AmountMinor: int64
          Currency: string
          Version: int64 }

    type QuoteError =
        | PriceNotFound
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | PriceNotFound -> "No price is available for this event and tier"
                | StoreUnavailable -> "Pricing store is unavailable"

    type ValidQuery = { Event: EventId; Tier: PriceTier }

    module ValidQuery =
        let parse (request: Request) : Result<ValidQuery, Cause> =
            result {
                let! event = EventId.parse request.Event
                and! tier = PriceTier.parse request.Tier
                return { Event = event; Tier = tier }
            }

    type Execute = Request -> Promise<Response>

    let quotePrice (store: PricingStore) : Execute =
        let lookup (query: ValidQuery) : Promise<Response> =
            let eventId = string (EventId.value query.Event)
            let tier = PriceTier.name query.Tier

            store.FindCurrent $"{eventId}:{tier}"
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (Promise.require PriceNotFound)
            |> Promise.map (fun row ->
                { Event = eventId
                  Tier = tier
                  AmountMinor = row.AmountMinor
                  Currency = row.Currency
                  Version = row.Version })

        fun request ->
            ValidQuery.parse request
            |> Promise.fromResult
            |> Promise.bind lookup
