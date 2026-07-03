namespace Ticketing.Availability.Projection

open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.Shared.Event

/// Projection: converge a seat to 'sold' when a `SeatSold` fact arrives.
/// Telescope leaf -- system `ticketing` -> subsystem `availability` -> workflow `projection` -> use
/// case `project-seat-sold`. Event consumer (no HTTP route); best-effort within the consumer: a
/// malformed fact or a transient store error is recovered to unit so the subscription never wedges
/// (design-out convergence keeps the projection eventually correct).
[<Slice>]
module ProjectSeatSold =
    type ValidSeatRef = { Seat: SeatId; Event: EventId }

    module ValidSeatRef =
        // Parse both fact ids into value objects; a malformed seat and event surface together.
        let parse (seat: string) (event: string) : Result<ValidSeatRef, Cause> =
            result {
                let! seatId = SeatId.parse seat
                and! eventId = EventId.parse event
                return { Seat = seatId; Event = eventId }
            }

    /// Java: `@SeatSoldSubscription` on execute -- the runtime wires the `seat-sold` topic here.
    type Execute = SeatSold -> Promise<unit>

    let projectSeatSold (store: SeatProjectionStore) : Execute =
        let convergeSold (ref: ValidSeatRef) : Promise<unit> =
            store.UpsertStatus(SeatId.value ref.Seat, EventId.value ref.Event, SeatState.dbValue SeatState.Sold)

        // JBCT pattern: Sequencer -- parse fact -> upsert projection -> recover.
        fun event ->
            ValidSeatRef.parse event.SeatId event.EventId
            |> Promise.fromResult
            |> Promise.bind convergeSold
            |> Promise.recover ignore
