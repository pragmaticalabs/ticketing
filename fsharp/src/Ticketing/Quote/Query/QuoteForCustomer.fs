namespace Ticketing.Quote.Query

open Pragmatica
open Aether
open Ticketing.Shared

/// Per-process projection row; field order matches the SELECT column order.
type PriceRow =
    { AmountMinor: int64
      Currency: string
      Tier: string
      Version: int64 }

/// Read-side persistence for `quote-for-customer`: looks up the latest projected price for a
/// `scope_key` (event:tier) in the `price_view` projection. One use case, one store, one query.
[<PgSql>]
type QuoteViewStore =
    [<Query("SELECT amount_minor, currency, tier, version FROM price_view WHERE scope_key = :scopeKey")>]
    abstract FindByScope: scopeKey: string -> Promise<PriceRow option>

/// Use case: quote the customer-facing current price for an (event, tier) from the read projection.
/// Telescope leaf -- system `ticketing` -> subsystem `quote` -> workflow `query` -> use case
/// `quote-for-customer`. One use case, one `Request`/`Response` pair, one `execute` function.
[<Slice>]
module QuoteForCustomer =
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
                | StoreUnavailable -> "Quote store is unavailable"

    type ValidQuery = { Event: EventId; Tier: PriceTier }

    module ValidQuery =
        let parse (request: Request) : Result<ValidQuery, Cause> =
            result {
                let! event = EventId.parse request.Event
                and! tier = PriceTier.parse request.Tier
                return { Event = event; Tier = tier }
            }

    type Execute = Request -> Promise<Response>

    let quoteForCustomer (store: QuoteViewStore) : Execute =
        let lookup (query: ValidQuery) : Promise<Response> =
            let event = string (EventId.value query.Event)
            let tier = PriceTier.name query.Tier

            store.FindByScope $"{event}:{tier}"
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (Promise.require PriceNotFound)
            |> Promise.map (fun row ->
                { Event = event
                  Tier = tier
                  AmountMinor = row.AmountMinor
                  Currency = row.Currency
                  Version = row.Version })

        fun request ->
            ValidQuery.parse request
            |> Promise.fromResult
            |> Promise.bind lookup
