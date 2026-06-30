package org.pragmatica.example.ticketing.availability.projection.projectseatsold;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.example.ticketing.availability.projection.SeatProjectionStore;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.SeatId;
import org.pragmatica.example.ticketing.shared.SeatState;
import org.pragmatica.example.ticketing.shared.event.SeatSold;
import org.pragmatica.example.ticketing.shared.event.SeatSoldSubscription;


/// Projection: converge a seat to 'sold' when a `SeatSold` fact arrives.
/// Telescope leaf — system `ticketing` → subsystem `availability` → workflow `projection` → use
/// case `project-seat-sold`. Event consumer (no HTTP route); best-effort within the consumer: a
/// malformed fact or a transient store error is recovered to Unit so the subscription never wedges
/// (design-out convergence keeps the projection eventually correct).
@Slice
public interface ProjectSeatSold {
    record ValidSeatRef(SeatId seat, EventId event) {
        // Parse both fact ids into value objects; a malformed seat and event surface together.
        static Result<ValidSeatRef> validSeatRef(String seat, String event) {
            return Result.all(SeatId.seatId(seat),
                              EventId.eventId(event))
                         .map(ValidSeatRef::new);
        }
    }

    @SeatSoldSubscription
    Promise<Unit> execute(SeatSold event);

    static ProjectSeatSold projectSeatSold(@PgSql SeatProjectionStore store) {
        record projectSeatSold(SeatProjectionStore store) implements ProjectSeatSold {
            // JBCT pattern: Sequencer -- parse fact -> upsert projection -> recover.
            @Override
            public Promise<Unit> execute(SeatSold event) {
                return ValidSeatRef.validSeatRef(event.seatId(),
                                                 event.eventId()).async()
                                                .flatMap(this::convergeSold)
                                                .recover(_ -> Unit.unit());
            }

            private Promise<Unit> convergeSold(ValidSeatRef ref) {
                return store.upsertStatus(ref.seat().value().value(),
                                          ref.event().value().value(),
                                          SeatState.SOLD.dbValue());
            }
        }

        return new projectSeatSold(store);
    }
}
