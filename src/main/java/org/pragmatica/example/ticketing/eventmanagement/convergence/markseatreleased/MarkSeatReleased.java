package org.pragmatica.example.ticketing.eventmanagement.convergence.markseatreleased;

import java.util.UUID;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.eventmanagement.EventStore.RowId;
import org.pragmatica.example.ticketing.eventmanagement.EventStore.SeatRow;
import org.pragmatica.example.ticketing.shared.SeatId;
import org.pragmatica.example.ticketing.shared.SeatState;
import org.pragmatica.example.ticketing.shared.event.SeatReleased;
import org.pragmatica.example.ticketing.shared.event.SeatReleasedSubscription;


/// Use case (event consumer, no HTTP route): converge a released seat back to 'available' on a
/// `SeatReleased` fact. Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow
/// `convergence` -> use case `mark-seat-released`. The exact mirror of `mark-seat-sold`.
///
/// Guarantees actually earned, both from the store's single guarded `UPDATE`, and both per seat:
///
///   - **authority** (`AND state = 'sold'`): only a sold seat is released, so a `blocked` seat is never
///     silently freed;
///   - **ordering** (`AND seats.version < :version`): the fact must advance the seat's per-seat sequence
///     (`reservations.version`, one row per seat, bumped by every booking lifecycle transition). A
///     `SeatReleased` that arrives after the `SeatSold` that superseded it carries the lower version and
///     is refused, so the seat correctly stays sold; a redelivery is a no-op.
///
/// The guard refuses silently, so a refusal is diagnosed by reading the seat back, and the stored
/// `version` separates the two refusals:
///
///   - stored version at or beyond the fact's -- already applied or overtaken; settled, absorbed as
///     success;
///   - stored version below the fact's -- the authority predicate refused: a genuine divergence,
///     reported as a typed [MarkSeatReleasedError].
///
/// That read is a separate statement, so the diagnosis is best-effort and can only ever mislabel toward
/// absorbing, never toward a false conflict.
///
/// Only an unparsable fact is discarded; a store outage and a convergence conflict both propagate, since
/// the failed `Promise` is the only evidence either produces.
///
/// NOT earned: **delivery**, exactly as in `mark-seat-sold`. Ordering only orders what arrives; the
/// current ephemeral pub-sub is at-most-once, so a dropped `SeatReleased` strands the seat as `sold`
/// here and no version guard can notice a version that never showed up.
///
/// JBCT-UC-02: a fact consumer's input IS the published `SeatReleased` fact -- that is the subscription
/// contract, so there is no Request/Response pair to declare.
@SuppressWarnings("JBCT-UC-02")
@Slice
public interface MarkSeatReleased {
    @MarkSeatReleasedLog
    @SeatReleasedSubscription
    Promise<Unit> execute(SeatReleased event);

    sealed interface MarkSeatReleasedError extends Cause {
        record SeatNotFound(String seat) implements MarkSeatReleasedError {
            @Override
            public String message() {
                return "SeatReleased fact refers to an unknown seat: " + seat;
            }
        }

        record SeatNotConvergible(String seat, SeatState state) implements MarkSeatReleasedError {
            @Override
            public String message() {
                return "Seat " + seat + " cannot converge to available from state " + state.dbValue();
            }
        }

        static MarkSeatReleasedError seatNotFound(UUID seat) {
            return new SeatNotFound(seat.toString());
        }

        static MarkSeatReleasedError seatNotConvergible(UUID seat, SeatState state) {
            return new SeatNotConvergible(seat.toString(), state);
        }
    }

    static MarkSeatReleased markSeatReleased(@PgSql EventStore store) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
        record markSeatReleased(EventStore store) implements MarkSeatReleased {
            // JBCT pattern: Condition -- bifurcate the inbound fact at the subsystem boundary: an
            // unparsable seat id is discarded, anything parsable is converged.
            @Override
            public Promise<Unit> execute(SeatReleased event) {
                return SeatId.seatId(event.seatId()).fold(_ -> Promise.UNIT,
                                                          seatId -> convergeReleased(seatId, event.version()));
            }

            private Promise<Unit> convergeReleased(SeatId seatId, long version) {
                var uuid = seatId.value().value();

                return store.markSeatAvailable(version, uuid)
                            .flatMap(applied -> confirmOrReconcile(applied, uuid, version));
            }

            // JBCT pattern: Condition -- a present projection is the applied transition; an empty one is
            // the guard refusing, which only a follow-up read can explain.
            private Promise<Unit> confirmOrReconcile(Option<RowId> applied, UUID seat, long version) {
                return applied.map(_ -> Promise.UNIT)
                              .or(() -> reconcile(seat, version));
            }

            private Promise<Unit> reconcile(UUID seat, long version) {
                return store.findSeat(seat)
                            .flatMap(found -> found.async(MarkSeatReleasedError.seatNotFound(seat)))
                            .flatMap(row -> classify(row, seat, version));
            }

            // JBCT pattern: Condition -- a seat already at or beyond this fact's position has either
            // absorbed it (re-delivery) or moved past it (overtaken), and either way it is settled; a
            // seat still behind it was refused by the authority predicate, which is a real divergence.
            private Promise<Unit> classify(SeatRow row, UUID seat, long version) {
                return row.version() >= version
                       ? Promise.UNIT
                       : MarkSeatReleasedError.seatNotConvergible(seat,
                                                                  row.state())
                                              .promise();
            }
        }

        return new markSeatReleased(store);
    }
}
