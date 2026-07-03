namespace Ticketing.EventManagement.Convergence

open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.Shared.Event
open Ticketing.EventManagement

/// Use case (event consumer, no HTTP route): converge authoritative seat status to 'sold' on a
/// `SeatSold` fact. Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow
/// `convergence` -> use case `mark-seat-sold`. Idempotent (design-out): a malformed fact or a
/// transient store error is recovered to unit so the subscription does not wedge.
[<Slice>]
module MarkSeatSold =
    /// Java: `@SeatSoldSubscription` on execute -- the runtime wires the `seat-sold` topic here.
    type Execute = SeatSold -> Promise<unit>

    let markSeatSold (store: EventStore) : Execute =
        let convergeSold (seatId: SeatId) : Promise<unit> = store.MarkSeatSold(SeatId.value seatId)

        // JBCT pattern: Sequencer -- parse fact -> converge seat status -> recover. Best-effort:
        // the at-most-once pub-sub discards the returned Promise, so recover keeps a poison fact
        // from wedging delivery.
        fun event ->
            SeatId.parse event.SeatId
            |> Promise.fromResult
            |> Promise.bind convergeSold
            |> Promise.recover ignore
