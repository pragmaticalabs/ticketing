namespace Ticketing.Booking.Hold

open Pragmatica
open Aether
open Ticketing.Shared.Event
open Ticketing.Booking

/// Use case: sweep expired holds and free their seats. Telescope leaf -- system `ticketing` ->
/// subsystem `booking` -> workflow `hold` -> use case `sweep-holds`. One use case, one
/// `Request`/`Response` pair, one `execute` function.
///
/// Recovery class: **FER** -- holds decay with time; this operator endpoint expires the held-but-
/// stale rows and publishes a `SeatReleased` fact per freed seat. Production would wire `execute` to
/// a `@Heartbeat` schedule; it is exposed as an endpoint here so the Iteration is drivable without
/// the scheduler. The empty request (Java: `record Request() {}`) keeps the one-parameter
/// slice-method contract; F#'s closest shape is a single-case marker union.
[<Slice>]
module SweepHolds =
    type Request = Request

    type Response = { Released: int64 }

    /// Closed set of sweep failures. Each is a distinct case so route error-mapping can target it
    /// by simple name (see routes.toml).
    type SweepError =
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | StoreUnavailable -> "Booking store is unavailable"

    type Execute = Request -> Promise<Response>

    let sweepHolds (store: BookingStore) (seatReleased: SeatReleasedPublisher) : Execute =
        let publishRelease (seat: SeatRef) : Promise<unit> =
            seatReleased
                { SeatId = string seat.SeatId
                  EventId = string seat.EventId }

        let releaseAll (seats: SeatRef list) : Promise<Response> =
            Promise.allOf (seats |> List.map publishRelease)
            |> Promise.map (fun _ -> { Released = int64 (List.length seats) })

        // JBCT pattern: Iteration -- expire held-but-stale rows, publish SeatReleased per freed seat.
        fun Request ->
            store.ExpireHolds()
            |> Promise.orFail StoreUnavailable
            |> Promise.bind releaseAll
