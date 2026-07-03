namespace Ticketing.EventManagement.Capacity

open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.EventManagement

/// Use case: add a seat to an existing event in 'available' state.
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `capacity` -> use
/// case `add-seat`. All-body request form: every field binds to a request field by name.
[<Slice>]
module AddSeat =
    type Request =
        { Event: string
          Section: string
          Row: string
          Number: int
          Tier: string }

    type Response = { Seat: string }

    type AddSeatError =
        | EventNotFound
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | EventNotFound -> "Event not found"
                | StoreUnavailable -> "Event management store is unavailable"

    type ValidAddSeat =
        { Event: EventId
          Location: SeatLocation
          Tier: PriceTier }

    module ValidAddSeat =
        // Parse raw request fields into value objects; all errors surface together via `and!`.
        let parse (request: Request) : Result<ValidAddSeat, Cause> =
            result {
                let! event = EventId.parse request.Event
                and! location = SeatLocation.parse request.Section request.Row request.Number
                and! tier = PriceTier.parse request.Tier
                return { Event = event; Location = location; Tier = tier }
            }

    type Execute = Request -> Promise<Response>

    let addSeat (store: EventStore) : Execute =
        let insertSeat (valid: ValidAddSeat) : Promise<Response> =
            let uuid = SeatId.value (SeatId.newId ())

            store.InsertSeat(
                uuid,
                EventId.value valid.Event,
                valid.Location.Section,
                valid.Location.Row,
                valid.Location.Number,
                PriceTier.name valid.Tier
            )
            |> Promise.orFail StoreUnavailable
            |> Promise.map (fun () -> { Seat = string uuid })

        // JBCT pattern: Condition -- route on event existence, no transformation.
        let insertSeatIfEventExists (exists: bool) (valid: ValidAddSeat) : Promise<Response> =
            if exists then insertSeat valid else Promise.fail EventNotFound

        let ensureEventThenInsert (valid: ValidAddSeat) : Promise<Response> =
            store.EventExists(EventId.value valid.Event)
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (fun exists -> insertSeatIfEventExists exists valid)

        // JBCT pattern: Sequencer -- validate -> ensure event exists -> insert seat.
        fun request ->
            ValidAddSeat.parse request
            |> Promise.fromResult
            |> Promise.bind ensureEventThenInsert
