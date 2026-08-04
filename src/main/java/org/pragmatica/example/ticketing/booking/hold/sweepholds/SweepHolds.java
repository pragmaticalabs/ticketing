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
///
/// JBCT-UC-02: the second entry method is `sweep()`, the zero-parameter `Promise<Unit>` entry the `Scheduled`
/// contract requires; it is excluded from route generation and `execute` remains the only Zone-1 entry.
@SuppressWarnings("JBCT-UC-02")
@Slice
public interface SweepHolds {
    record Request() {}

    record Response(long released) {}

    Promise<Response> execute(Request request);

    /// Scheduler entry point: zero parameters, `Promise<Unit>` (the rc2 `Scheduled` contract);
    /// excluded from route generation. Delegates to `execute`.
    @SweepSchedule
    Promise<Unit> sweep();

    /// Closed set of sweep failures. Fixed-message refusals are grouped into one enum per HTTP status
    /// so route error-mapping can target a whole status class by that enum's simple name (see
    /// routes.toml); data-carrying refusals stay records.
    sealed interface SweepError extends Cause {
        /// Fixed-message failures of a dependency this slice calls. Every constant here is an HTTP 503 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 503 must not be added to it, and one
        /// `*ServiceUnavailable*` pattern maps the whole enum.
        enum ServiceUnavailable implements SweepError {
            BOOKING_STORE("Booking store is unavailable");
            private final String message;
            ServiceUnavailable(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        static SweepError storeUnavailable() {
            return ServiceUnavailable.BOOKING_STORE;
        }
    }

    static SweepHolds sweepHolds(@PgSql BookingStore store,
                                 @SeatReleasedPublisher Publisher<SeatReleased> seatReleased) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
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
