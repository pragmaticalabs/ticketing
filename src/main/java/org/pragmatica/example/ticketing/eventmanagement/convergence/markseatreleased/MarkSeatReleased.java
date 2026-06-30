package org.pragmatica.example.ticketing.eventmanagement.convergence.markseatreleased;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.shared.SeatId;
import org.pragmatica.example.ticketing.shared.event.SeatReleased;
import org.pragmatica.example.ticketing.shared.event.SeatReleasedSubscription;


/// Use case (event consumer, no HTTP route): converge a released seat back to 'available' on a
/// `SeatReleased` fact. Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` ->
/// workflow `convergence` -> use case `mark-seat-released`. Idempotent (design-out): a malformed fact
/// or a transient store error is recovered to Unit so the subscription does not wedge.
@Slice
public interface MarkSeatReleased {
    @SeatReleasedSubscription
    Promise<Unit> execute(SeatReleased event);

    static MarkSeatReleased markSeatReleased(@PgSql EventStore store) {
        record markSeatReleased(EventStore store) implements MarkSeatReleased {
            // JBCT pattern: Sequencer -- parse fact -> converge seat status -> recover. Best-effort:
            // the at-most-once pub-sub discards the returned Promise, so recover keeps a poison fact
            // from wedging delivery.
            @Override
            public Promise<Unit> execute(SeatReleased event) {
                return SeatId.seatId(event.seatId())
                             .async()
                             .flatMap(this::convergeReleased)
                             .recover(_ -> Unit.unit());
            }

            private Promise<Unit> convergeReleased(SeatId seatId) {
                return store.markSeatAvailable(seatId.value().value());
            }
        }

        return new markSeatReleased(store);
    }
}
