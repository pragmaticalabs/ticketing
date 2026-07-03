namespace Ticketing.Availability.Query

open System
open Pragmatica
open Aether
open Ticketing.Shared

/// Persistence for the `sold-count` query use case: count the sold seats of an event from the
/// `seat_availability` projection. Single-statement, validator-friendly SQL only. Per-use-case
/// store co-located with its slice (interface segregation -- no read slice needs more than one
/// method).
[<PgSql>]
type SoldCountStore =
    [<Query("SELECT count(*) FROM seat_availability WHERE event_id = :eventId AND state = 'sold'")>]
    abstract CountSold: eventId: Guid -> Promise<int64>

/// Use case: count the sold seats of an event from the availability projection.
/// Telescope leaf -- system `ticketing` -> subsystem `availability` -> workflow `query` -> use case
/// `sold-count`. One use case, one `Request`/`Response` pair, one `execute` function.
[<Slice>]
module SoldCount =
    type Request = { Event: string }

    type Response = { Event: string; Sold: int64 }

    type AvailabilityError =
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | StoreUnavailable -> "Availability store is unavailable"

    type Execute = Request -> Promise<Response>

    let soldCount (store: SoldCountStore) : Execute =
        let countFor (eventId: EventId) : Promise<Response> =
            let event = string (EventId.value eventId)

            store.CountSold(EventId.value eventId)
            |> Promise.orFail StoreUnavailable
            |> Promise.map (fun sold -> { Event = event; Sold = sold })

        // JBCT pattern: Sequencer -- validate -> count -> respond.
        fun request ->
            EventId.parse request.Event
            |> Promise.fromResult
            |> Promise.bind countFor
