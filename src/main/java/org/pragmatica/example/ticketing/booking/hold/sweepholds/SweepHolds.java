package org.pragmatica.example.ticketing.booking.hold.sweepholds;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.example.ticketing.booking.BookingStore;
import org.pragmatica.example.ticketing.booking.BookingStore.SeatRef;
import org.pragmatica.example.ticketing.shared.event.SeatReleased;
import org.pragmatica.example.ticketing.shared.event.SeatReleasedPublisher;


/// Use case: sweep the reservations that time has invalidated and free their seats. Telescope leaf
/// -- system `ticketing` -> subsystem `booking` -> workflow `hold` -> use case `sweep-holds`. One
/// use case, one `Request`/`Response` pair, one `execute` method.
///
/// Recovery class: **FER** -- reservations decay with time, and this sweep is the only process that
/// reclaims the two kinds that no live request will ever free:
///   - **expired holds**: state 'held' past their TTL;
///   - **orphaned confirmations**: state 'confirmed' with no confirmed booking, left by a crash
///     between confirming a reservation and writing its booking row, or by a cancellation whose
///     final booking write failed. The claim guard never admits 'confirmed', so without this reaper
///     the seat is unreclaimable forever. It is bounded by claim age so it cannot race a purchase
///     in flight.
///
/// Both publish a `SeatReleased` fact per freed seat and are counted together in `released`.
/// `sweep()` runs on the runtime scheduler (rc2 `Scheduled`, cadence in `[scheduling.sweep-holds]`
/// in resources.toml); the HTTP endpoint on `execute` remains as an operator escape hatch. The empty
/// `Request` record keeps the one-parameter slice-method contract.
@Slice
public interface SweepHolds {
    record Request() {}

    record Response(long released) {}

    /// Closed set of sweep failures. Each is a distinct record so route error-mapping can target it
    /// by simple name (see routes.toml).
    sealed interface SweepError extends Cause {
        record StoreUnavailable() implements SweepError {
            @Override
            public String message() {
                return "Booking store is unavailable";
            }
        }

        static SweepError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    /// Scheduler entry point: zero parameters, `Promise<Unit>` (the rc2 `Scheduled` contract);
    /// excluded from route generation. Delegates to `execute`.
    @SweepSchedule
    Promise<Unit> sweep();

    static SweepHolds sweepHolds(@PgSql BookingStore store,
                                 @SeatReleasedPublisher Publisher<SeatReleased> seatReleased) {
        @SuppressWarnings("JBCT-SEQ-01")
        record sweepHolds(BookingStore store, Publisher<SeatReleased> seatReleased) implements SweepHolds {
            // JBCT pattern: Fork-Join -- the two reapers touch disjoint rows (held vs. confirmed) and
            // are independent, so they run in parallel; one fact is published per freed seat.
            @Override
            public Promise<Response> execute(Request request) {
                return Promise.all(store.expireHolds(),
                                   store.expireOrphanedConfirmations())
                              .map(this::freedSeats)
                              .mapError(_ -> SweepError.storeUnavailable())
                              .flatMap(this::releaseAll);
            }

            private List<SeatRef> freedSeats(List<SeatRef> expiredHolds, List<SeatRef> orphans) {
                return Stream.concat(expiredHolds.stream(),
                                     orphans.stream())
                             .toList();
            }

            private Promise<Response> releaseAll(List<SeatRef> seats) {
                return Promise.allOf(seats.stream().map(this::publishRelease).toList()).map(_ -> new Response(seats.size()));
            }

            private Promise<Unit> publishRelease(SeatRef seat) {
                return seatReleased.publish(new SeatReleased(seat.seatId().toString(),
                                                             seat.eventId().toString(),
                                                             seat.version()));
            }

            @Override
            public Promise<Unit> sweep() {
                return execute(new Request()).mapToUnit();
            }
        }

        return new sweepHolds(store, seatReleased);
    }
}
