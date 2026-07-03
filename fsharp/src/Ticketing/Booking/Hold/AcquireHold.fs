namespace Ticketing.Booking.Hold

open System
open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.Booking

/// Use case: claim a seat with a decaying 15-minute hold (FER). Telescope leaf -- system
/// `ticketing` -> subsystem `booking` -> workflow `hold` -> use case `acquire-hold`. One use case,
/// one `Request`/`Response` pair, one `execute` function.
///
/// Recovery class: **design-out** -- the hold is the same single guarded seat claim used by the buy
/// saga (state 'held'); the loser of a contended seat fast-fails with SeatUnavailable.
[<Slice>]
module AcquireHold =
    type Request =
        { Customer: string
          Event: string
          Seat: string }

    type Response = { Reservation: string; State: string }

    /// Closed set of acquire failures. Each is a distinct case so route error-mapping can target it
    /// by simple name (see routes.toml).
    type AcquireError =
        | SeatUnavailable
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | SeatUnavailable -> "Seat is no longer available"
                | StoreUnavailable -> "Booking store is unavailable"

    /// Validated hold-acquire target.
    type ValidAcquire =
        { Customer: CustomerId
          Event: EventId
          Seat: SeatId }

    module ValidAcquire =
        let parse (request: Request) : Result<ValidAcquire, Cause> =
            result {
                let! customer = CustomerId.parse request.Customer
                and! event = EventId.parse request.Event
                and! seat = SeatId.parse request.Seat
                return { Customer = customer; Event = event; Seat = seat }
            }

    type Execute = Request -> Promise<Response>

    let acquireHold (store: BookingStore) : Execute =
        let claimHold (valid: ValidAcquire) : Promise<Response> =
            let reservationId = Guid.NewGuid()

            store.ClaimSeat(reservationId, SeatId.value valid.Seat, EventId.value valid.Event,
                            CustomerId.value valid.Customer)
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (Promise.require SeatUnavailable)
            |> Promise.map (fun _ -> { Reservation = string reservationId; State = "FRESH" })

        // JBCT pattern: Sequencer -- validate -> design-out hold claim (state 'held' with a TTL).
        fun request ->
            ValidAcquire.parse request
            |> Promise.fromResult
            |> Promise.bind claimHold
