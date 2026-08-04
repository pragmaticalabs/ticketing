package org.pragmatica.example.ticketing.availability.projection.projectseatreleased;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.example.ticketing.availability.projection.SeatProjectionStore;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.SeatId;
import org.pragmatica.example.ticketing.shared.SeatState;
import org.pragmatica.example.ticketing.shared.event.SeatReleased;
import org.pragmatica.example.ticketing.shared.event.SeatReleasedSubscription;


/// Projection: converge a seat back to 'available' when a `SeatReleased` fact arrives.
/// Telescope leaf — system `ticketing` → subsystem `availability` → workflow `projection` → use
/// case `project-seat-released`. Event consumer (no HTTP route); best-effort within the consumer: a
/// malformed fact or a transient store error is recovered to Unit so the subscription never wedges.
/// Ordering is not this slice's job: it forwards the fact's per-seat `version` and the store's
/// `WHERE seat_availability.version < EXCLUDED.version` guard discards a release that was overtaken
/// by a later sale of the same seat.
@Slice
public interface ProjectSeatReleased {
    record ValidSeatRef(SeatId seat, EventId event, long version) {
        // Parse both fact ids into value objects; a malformed seat and event surface together. The
        // version needs no parsing -- it is the store's ordering key, not a domain value.
        static Result<ValidSeatRef> validSeatRef(String seat, String event, long version) {
            return Result.all(SeatId.seatId(seat),
                              EventId.eventId(event))
                         .map((seatId, eventId) -> new ValidSeatRef(seatId, eventId, version));
        }
    }

    @ProjectSeatReleasedLog
    @SeatReleasedSubscription
    Promise<Unit> execute(SeatReleased event);

    static ProjectSeatReleased projectSeatReleased(@PgSql SeatProjectionStore store) {
        record projectSeatReleased(SeatProjectionStore store) implements ProjectSeatReleased {
            // JBCT pattern: Sequencer -- parse fact -> upsert projection -> recover.
            @Override
            public Promise<Unit> execute(SeatReleased event) {
                return ValidSeatRef.validSeatRef(event.seatId(),
                                                 event.eventId(),
                                                 event.version())
                                   .async()
                                   .flatMap(this::convergeReleased)
                                   .recover(_ -> Unit.unit());
            }

            private Promise<Unit> convergeReleased(ValidSeatRef ref) {
                return store.upsertStatus(ref.seat().value().value(),
                                          ref.event().value().value(),
                                          SeatState.AVAILABLE,
                                          ref.version());
            }
        }

        return new projectSeatReleased(store);
    }
}
