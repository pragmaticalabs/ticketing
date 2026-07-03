namespace Ticketing.Booking.Hold

open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.Booking

/// Use case: report the decay state of a seat's hold (FRESH / STALE / EXPIRED / NONE). Telescope
/// leaf -- system `ticketing` -> subsystem `booking` -> workflow `hold` -> use case `check-hold`.
/// One use case, one `Request`/`Response` pair, one `execute` function.
///
/// Recovery class: **FER** -- the hold decays with time; the read maps the persisted state plus the
/// time-as-decay flags to a single label.
[<Slice>]
module CheckHold =
    type Request = { Seat: string }

    type Response = { Seat: string; State: string }

    /// Closed set of check failures. Each is a distinct case so route error-mapping can target it
    /// by simple name (see routes.toml).
    type CheckError =
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | StoreUnavailable -> "Booking store is unavailable"

    type Execute = Request -> Promise<Response>

    let checkHold (store: BookingStore) : Execute =
        // JBCT pattern: Condition (pure) -- map the persisted state + decay flags to a label. One
        // match replaces the Java classify/staleOrFresh helper pair.
        let decayState (found: HoldRow option) : string =
            match found with
            | None -> "NONE"
            | Some row when row.Expired -> "EXPIRED"
            | Some row when row.Stale -> "STALE"
            | Some _ -> "FRESH"

        let loadHoldState (seatId: SeatId) : Promise<Response> =
            let seat = string (SeatId.value seatId)

            store.HoldDecay(SeatId.value seatId)
            |> Promise.orFail StoreUnavailable
            |> Promise.map (fun found -> { Seat = seat; State = decayState found })

        // JBCT pattern: Sequencer -- validate -> read the hold's decay snapshot.
        fun request ->
            SeatId.parse request.Seat
            |> Promise.fromResult
            |> Promise.bind loadHoldState
