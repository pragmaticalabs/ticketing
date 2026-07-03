namespace Ticketing.EventManagement.Lifecycle

open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.EventManagement

/// Use case: register a new event in 'draft' state.
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `lifecycle` -> use
/// case `create-event`. One use case, one `Request`/`Response` pair, one `execute` function.
[<Slice>]
module CreateEvent =
    type Request = { Venue: string; OnSaleAt: string }

    type Response = { Event: string }

    type CreateEventError =
        | BlankVenue
        | MalformedOnSaleAt of raw: string
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | BlankVenue -> "Venue must not be blank"
                | MalformedOnSaleAt raw -> $"On-sale time is not a valid ISO-8601 timestamp: {raw}"
                | StoreUnavailable -> "Event management store is unavailable"

    /// Validated create request: a non-blank venue and a parsed ISO-8601 on-sale timestamp. Both
    /// failures surface together via `and!`, so a blank/garbage timestamp can no longer be
    /// persisted verbatim.
    type ValidCreateEvent = { Venue: string; OnSaleAt: IsoDateTime }

    module ValidCreateEvent =
        let parse (request: Request) : Result<ValidCreateEvent, Cause> =
            result {
                let! venue = request.Venue |> Verify.ensure Verify.Is.present BlankVenue
                and! onSaleAt = IsoDateTime.parse request.OnSaleAt |> Result.orFail (MalformedOnSaleAt request.OnSaleAt)
                return { Venue = venue; OnSaleAt = onSaleAt }
            }

    type Execute = Request -> Promise<Response>

    let createEvent (store: EventStore) : Execute =
        let register (valid: ValidCreateEvent) : Promise<Response> =
            let uuid = EventId.value (EventId.newId ())

            store.InsertEvent(uuid, valid.Venue, IsoDateTime.render valid.OnSaleAt)
            |> Promise.orFail StoreUnavailable
            |> Promise.map (fun () -> { Event = string uuid })

        // JBCT pattern: Sequencer -- validate venue + on-sale time -> register event.
        fun request ->
            ValidCreateEvent.parse request
            |> Promise.fromResult
            |> Promise.bind register
