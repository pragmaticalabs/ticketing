namespace Ticketing.Availability.Query

open System
open Pragmatica
open Aether
open Ticketing.Shared

/// Per-process projection row. Field order matches the SELECT column order.
type StatusRow = { State: string }

/// Persistence for the `seat-status` query use case: read a single seat's latest status from the
/// `seat_availability` projection. Single-statement, validator-friendly SQL only. A seat with no
/// projection row was never sold/held, so `FindStatus` reads as empty (the slice defaults it to
/// available). Per-use-case store co-located with its slice (interface segregation -- no read slice
/// needs more than one method).
[<PgSql>]
type SeatStatusStore =
    [<Query("SELECT state FROM seat_availability WHERE seat_id = :seatId")>]
    abstract FindStatus: seatId: Guid -> Promise<StatusRow option>

/// Use case: read the latest status of a single seat from the availability projection.
/// Telescope leaf -- system `ticketing` -> subsystem `availability` -> workflow `query` -> use case
/// `seat-status`. One use case, one `Request`/`Response` pair, one `execute` function. A seat with no
/// projection row was never sold/held, so it reads as available.
[<Slice>]
module SeatStatus =
    type Request = { Seat: string }

    type Response = { Seat: string; State: string }

    type AvailabilityError =
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | StoreUnavailable -> "Availability store is unavailable"

    type Execute = Request -> Promise<Response>

    let seatStatus (store: SeatStatusStore) : Execute =
        // A missing row means the seat was never sold/held -- default to available.
        let statusOf (found: StatusRow option) : string =
            found
            |> Option.map (fun row -> row.State)
            |> Option.defaultValue (SeatState.dbValue SeatState.Available)

        let lookup (seatId: SeatId) : Promise<Response> =
            let seat = string (SeatId.value seatId)

            store.FindStatus(SeatId.value seatId)
            |> Promise.orFail StoreUnavailable
            |> Promise.map (fun found -> { Seat = seat; State = statusOf found })

        // JBCT pattern: Sequencer -- validate -> read -> respond.
        fun request ->
            SeatId.parse request.Seat
            |> Promise.fromResult
            |> Promise.bind lookup
