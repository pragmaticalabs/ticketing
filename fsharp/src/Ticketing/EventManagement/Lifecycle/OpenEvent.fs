namespace Ticketing.EventManagement.Lifecycle

open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.EventManagement

/// Use case: move a draft event to 'on_sale' (guarded transition, no fact published).
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `lifecycle` -> use
/// case `open-event`. One use case, one `Request`/`Response` pair, one `execute` function.
[<Slice>]
module OpenEvent =
    type Request = { Event: string }

    type Response = { Event: string }

    type OpenEventError =
        | EventNotFound
        | AlreadyOpen
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | EventNotFound -> "Event not found"
                | AlreadyOpen -> "Event is already open for sale"
                | StoreUnavailable -> "Event management store is unavailable"

    type Execute = Request -> Promise<Response>

    let openEvent (store: EventStore) : Execute =
        let doOpen (eventId: EventId) : Promise<Response> =
            let uuid = EventId.value eventId

            store.OpenEvent uuid
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (Promise.require AlreadyOpen)
            |> Promise.map (fun _ -> { Event = string uuid })

        // JBCT pattern: Condition -- route on event existence, no transformation.
        let openIfEventExists (exists: bool) (eventId: EventId) : Promise<Response> =
            if exists then doOpen eventId else Promise.fail EventNotFound

        let ensureEventThenOpen (eventId: EventId) : Promise<Response> =
            store.EventExists(EventId.value eventId)
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (fun exists -> openIfEventExists exists eventId)

        // JBCT pattern: Sequencer -- validate -> ensure event exists -> guarded open.
        fun request ->
            EventId.parse request.Event
            |> Promise.fromResult
            |> Promise.bind ensureEventThenOpen
