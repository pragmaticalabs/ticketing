namespace Ticketing.EventManagement.Convergence

open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.Shared.Event
open Ticketing.EventManagement

/// Use case (event consumer, no HTTP route): converge a released seat back to 'available' on a
/// `SeatReleased` fact. Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` ->
/// workflow `convergence` -> use case `mark-seat-released`. Idempotent (design-out): a malformed fact
/// or a transient store error is recovered to unit so the subscription does not wedge.
[<Slice>]
module MarkSeatReleased =
    /// Java: `@SeatReleasedSubscription` on execute -- the runtime wires the `seat-released` topic here.
    type Execute = SeatReleased -> Promise<unit>

    let markSeatReleased (store: EventStore) : Execute =
        let convergeReleased (seatId: SeatId) : Promise<unit> =
            store.MarkSeatAvailable(SeatId.value seatId)

        // JBCT pattern: Sequencer -- parse fact -> converge seat status -> recover. Best-effort:
        // the at-most-once pub-sub discards the returned Promise, so recover keeps a poison fact
        // from wedging delivery.
        fun event ->
            SeatId.parse event.SeatId
            |> Promise.fromResult
            |> Promise.bind convergeReleased
            |> Promise.recover ignore
