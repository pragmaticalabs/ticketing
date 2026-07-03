namespace Ticketing.EventManagement.Lifecycle

open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.EventManagement

/// Use case: withdraw an event (guarded transition to 'cancelled').
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `lifecycle` -> use
/// case `cancel-event`. One use case, one `Request`/`Response` pair, one `execute` function.
[<Slice>]
module CancelEvent =
    type Request = { Event: string }

    type Response = { Event: string }

    type CancelEventError =
        | EventNotFound
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | EventNotFound -> "Event not found"
                | StoreUnavailable -> "Event management store is unavailable"

    type Execute = Request -> Promise<Response>

    let cancelEvent (store: EventStore) : Execute =
        let doCancel (eventId: EventId) : Promise<Response> =
            let uuid = EventId.value eventId

            store.CancelEvent uuid
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (Promise.require EventNotFound)
            |> Promise.map (fun _ -> { Event = string uuid })

        // JBCT pattern: Sequencer -- validate -> guarded cancel update.
        fun request ->
            EventId.parse request.Event
            |> Promise.fromResult
            |> Promise.bind doCancel
