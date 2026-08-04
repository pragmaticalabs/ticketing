package org.pragmatica.example.ticketing.eventmanagement.convergence.markseatsold;

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
import org.pragmatica.example.ticketing.shared.event.SeatSold;
import org.pragmatica.example.ticketing.shared.event.SeatSoldSubscription;


/// Use case (event consumer, no HTTP route): converge authoritative seat status to 'sold' on a
/// `SeatSold` fact. Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow
/// `convergence` -> use case `mark-seat-sold`.
///
/// Guarantees actually earned, both from the store's single guarded `UPDATE`, and both per seat:
///
///   - **authority** (`AND state = 'available'`): only a seat genuinely on sale can be sold, so this
///     slice can never overwrite a `blocked` or `withdrawn` seat;
///   - **ordering** (`AND seats.version < :version`): the fact must advance the seat's per-seat
///     sequence, so of the facts that are delivered, the seat ends at the highest-versioned one
///     whatever order they arrive in. A `SeatReleased` that overtakes its `SeatSold` no longer frees
///     a sold seat, and a redelivery is a no-op. The sequence is `reservations.version` -- one row
///     per seat, bumped by every booking lifecycle transition -- so it totally orders the facts for
///     one seat and says nothing across seats, which is all this slice needs.
///
/// The guard refuses silently, so a refusal is diagnosed by reading the seat back, and the stored
/// `version` is what separates the two refusals:
///
///   - stored version at or beyond the fact's -- the fact was already applied or has been overtaken
///     by a later transition; it is settled, and is absorbed as success;
///   - stored version below the fact's -- the authority predicate is what refused: booking and
///     eventmanagement genuinely disagree, reported as a typed [MarkSeatSoldError], not a silent
///     success.
///
/// That read is a separate statement, so the diagnosis is best-effort: it can observe a state newer
/// than the one that refused. It only ever mislabels toward absorbing, never toward a false conflict.
///
/// Only an unparsable fact is discarded: no retry could ever settle it, so absorbing it is what keeps a
/// poison message from re-presenting forever. A store outage and a convergence conflict both propagate,
/// because the failed `Promise` is the only evidence either ever produces.
///
/// NOT earned: **delivery**. Ordering only orders what arrives. Under the current ephemeral pub-sub
/// delivery is at-most-once, so a dropped `SeatSold` leaves the seat permanently `available` here while
/// booking holds it sold, and nothing in this slice detects the gap -- a version guard cannot notice a
/// version that never showed up. Closing that needs durable subscriptions or a reconciliation sweep,
/// neither of which exists yet.
///
/// JBCT-UC-02: a fact consumer's input IS the published `SeatSold` fact -- that is the subscription
/// contract, so there is no Request/Response pair to declare.
@SuppressWarnings("JBCT-UC-02")
@Slice
public interface MarkSeatSold {
    @MarkSeatSoldLog
    @SeatSoldSubscription
    Promise<Unit> execute(SeatSold event);

    sealed interface MarkSeatSoldError extends Cause {
        record SeatNotFound(String seat) implements MarkSeatSoldError {
            @Override
            public String message() {
                return "SeatSold fact refers to an unknown seat: " + seat;
            }
        }

        record SeatNotConvergible(String seat, SeatState state) implements MarkSeatSoldError {
            @Override
            public String message() {
                return "Seat " + seat + " cannot converge to sold from state " + state.dbValue();
            }
        }

        static MarkSeatSoldError seatNotFound(UUID seat) {
            return new SeatNotFound(seat.toString());
        }

        static MarkSeatSoldError seatNotConvergible(UUID seat, SeatState state) {
            return new SeatNotConvergible(seat.toString(), state);
        }
    }

    static MarkSeatSold markSeatSold(@PgSql EventStore store) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
        record markSeatSold(EventStore store) implements MarkSeatSold {
            // JBCT pattern: Condition -- bifurcate the inbound fact at the subsystem boundary: an
            // unparsable seat id is discarded, anything parsable is converged.
            @Override
            public Promise<Unit> execute(SeatSold event) {
                return SeatId.seatId(event.seatId()).fold(_ -> Promise.UNIT,
                                                          seatId -> convergeSold(seatId, event.version()));
            }

            private Promise<Unit> convergeSold(SeatId seatId, long version) {
                var uuid = seatId.value().value();

                return store.markSeatSold(version, uuid)
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
                            .flatMap(found -> found.async(MarkSeatSoldError.seatNotFound(seat)))
                            .flatMap(row -> classify(row, seat, version));
            }

            // JBCT pattern: Condition -- a seat already at or beyond this fact's position has either
            // absorbed it (re-delivery) or moved past it (overtaken), and either way it is settled; a
            // seat still behind it was refused by the authority predicate, which is a real divergence.
            private Promise<Unit> classify(SeatRow row, UUID seat, long version) {
                return row.version() >= version
                       ? Promise.UNIT
                       : MarkSeatSoldError.seatNotConvergible(seat,
                                                              row.state())
                                          .promise();
            }
        }

        return new markSeatSold(store);
    }
}
