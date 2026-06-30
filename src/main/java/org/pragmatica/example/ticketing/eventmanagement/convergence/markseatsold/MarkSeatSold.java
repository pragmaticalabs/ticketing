package org.pragmatica.example.ticketing.eventmanagement.convergence.markseatsold;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.shared.SeatId;
import org.pragmatica.example.ticketing.shared.event.SeatSold;
import org.pragmatica.example.ticketing.shared.event.SeatSoldSubscription;


/// Use case (event consumer, no HTTP route): converge authoritative seat status to 'sold' on a
/// `SeatSold` fact. Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow
/// `convergence` -> use case `mark-seat-sold`. Idempotent (design-out): a malformed fact or a
/// transient store error is recovered to Unit so the subscription does not wedge.
@Slice
public interface MarkSeatSold {
    @SeatSoldSubscription
    Promise<Unit> execute(SeatSold event);

    static MarkSeatSold markSeatSold(@PgSql EventStore store) {
        record markSeatSold(EventStore store) implements MarkSeatSold {
            // JBCT pattern: Sequencer -- parse fact -> converge seat status -> recover. Best-effort:
            // the at-most-once pub-sub discards the returned Promise, so recover keeps a poison fact
            // from wedging delivery.
            @Override
            public Promise<Unit> execute(SeatSold event) {
                return SeatId.seatId(event.seatId())
                             .async()
                             .flatMap(this::convergeSold)
                             .recover(_ -> Unit.unit());
            }

            private Promise<Unit> convergeSold(SeatId seatId) {
                return store.markSeatSold(seatId.value().value());
            }
        }

        return new markSeatSold(store);
    }
}
