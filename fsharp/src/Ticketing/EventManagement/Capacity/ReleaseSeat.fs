namespace Ticketing.EventManagement.Capacity

open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.EventManagement

/// Use case (BER, inverse of block-seat): release a blocked seat back to inventory.
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `capacity` -> use
/// case `release-seat`. Guarded transition: releases only a seat currently 'blocked'.
[<Slice>]
module ReleaseSeat =
    type Request = { Seat: string }

    type Response = { Seat: string }

    type ReleaseSeatError =
        | SeatNotBlocked
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | SeatNotBlocked -> "Seat is not blocked"
                | StoreUnavailable -> "Event management store is unavailable"

    type Execute = Request -> Promise<Response>

    let releaseSeat (store: EventStore) : Execute =
        let doRelease (seatId: SeatId) : Promise<Response> =
            let uuid = SeatId.value seatId

            store.ReleaseSeat uuid
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (Promise.require SeatNotBlocked)
            |> Promise.map (fun _ -> { Seat = string uuid })

        // JBCT pattern: Sequencer -- validate -> guarded release update.
        fun request ->
            SeatId.parse request.Seat
            |> Promise.fromResult
            |> Promise.bind doRelease
