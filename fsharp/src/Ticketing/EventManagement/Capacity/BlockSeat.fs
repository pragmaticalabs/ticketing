namespace Ticketing.EventManagement.Capacity

open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.EventManagement

/// Use case (BER, inverse of release-seat): block an available seat.
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `capacity` -> use
/// case `block-seat`. Guarded transition: blocks only a seat currently 'available'.
[<Slice>]
module BlockSeat =
    type Request = { Seat: string }

    type Response = { Seat: string }

    type BlockSeatError =
        | SeatUnavailable
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | SeatUnavailable -> "Seat is not available to block"
                | StoreUnavailable -> "Event management store is unavailable"

    type Execute = Request -> Promise<Response>

    let blockSeat (store: EventStore) : Execute =
        let doBlock (seatId: SeatId) : Promise<Response> =
            let uuid = SeatId.value seatId

            store.BlockSeat uuid
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (Promise.require SeatUnavailable)
            |> Promise.map (fun _ -> { Seat = string uuid })

        // JBCT pattern: Sequencer -- validate -> guarded block update.
        fun request ->
            SeatId.parse request.Seat
            |> Promise.fromResult
            |> Promise.bind doBlock
