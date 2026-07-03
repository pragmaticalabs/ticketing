namespace Ticketing.EventManagement.Sales

open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.EventManagement

/// Use case: read whether an event is currently selling (direct read, also called by booking).
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `sales` -> use case
/// `sale-status`. One use case, one `Request`/`Response` pair, one `execute` function.
[<Slice>]
module SaleStatus =
    type Request = { Event: string }

    type Response =
        { Event: string
          OnSale: bool
          OnSaleAt: string }

    type SaleStatusError =
        | EventNotFound
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | EventNotFound -> "Event not found"
                | StoreUnavailable -> "Event management store is unavailable"

    type Execute = Request -> Promise<Response>

    let saleStatus (store: EventStore) : Execute =
        let loadSaleStatus (eventId: EventId) : Promise<Response> =
            let eventString = string (EventId.value eventId)

            store.FindEvent(EventId.value eventId)
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (Promise.require EventNotFound)
            |> Promise.map (fun row ->
                { Event = eventString
                  OnSale = row.Status = "on_sale"
                  OnSaleAt = row.OnSaleAt })

        // JBCT pattern: Sequencer -- validate -> read current state.
        fun request ->
            EventId.parse request.Event
            |> Promise.fromResult
            |> Promise.bind loadSaleStatus
