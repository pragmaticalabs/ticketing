namespace Ticketing.Availability.Projection

open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.Shared.Event

/// Projection: converge a seat back to 'available' when a `SeatReleased` fact arrives.
/// Telescope leaf -- system `ticketing` -> subsystem `availability` -> workflow `projection` -> use
/// case `project-seat-released`. Event consumer (no HTTP route); best-effort within the consumer: a
/// malformed fact or a transient store error is recovered to unit so the subscription never wedges
/// (design-out convergence keeps the projection eventually correct).
[<Slice>]
module ProjectSeatReleased =
    type ValidSeatRef = { Seat: SeatId; Event: EventId }

    module ValidSeatRef =
        // Parse both fact ids into value objects; a malformed seat and event surface together.
        let parse (seat: string) (event: string) : Result<ValidSeatRef, Cause> =
            result {
                let! seatId = SeatId.parse seat
                and! eventId = EventId.parse event
                return { Seat = seatId; Event = eventId }
            }

    /// Java: `@SeatReleasedSubscription` on execute -- the runtime wires the `seat-released` topic here.
    type Execute = SeatReleased -> Promise<unit>

    let projectSeatReleased (store: SeatProjectionStore) : Execute =
        let convergeReleased (ref: ValidSeatRef) : Promise<unit> =
            store.UpsertStatus(SeatId.value ref.Seat, EventId.value ref.Event, SeatState.dbValue SeatState.Available)

        // JBCT pattern: Sequencer -- parse fact -> upsert projection -> recover.
        fun event ->
            ValidSeatRef.parse event.SeatId event.EventId
            |> Promise.fromResult
            |> Promise.bind convergeReleased
            |> Promise.recover ignore
