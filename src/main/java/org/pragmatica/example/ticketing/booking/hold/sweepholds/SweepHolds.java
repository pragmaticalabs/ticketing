package org.pragmatica.example.ticketing.booking.hold.sweepholds;

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

import java.util.List;


/// Use case: sweep expired holds and free their seats. Telescope leaf -- system `ticketing` ->
/// subsystem `booking` -> workflow `hold` -> use case `sweep-holds`. One use case, one
/// `Request`/`Response` pair, one `execute` method.
///
/// Recovery class: **FER** -- holds decay with time; this operator endpoint expires the held-but-
/// stale rows and publishes a `SeatReleased` fact per freed seat. Production would wire `execute` to
/// a `@Heartbeat` schedule; it is exposed as an endpoint here so the Iteration is drivable without
/// the scheduler. The empty `Request` record keeps the one-parameter slice-method contract.
@Slice
public interface SweepHolds {
    record Request() {}

    @SuppressWarnings("JBCT-VO-01")
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

    static SweepHolds sweepHolds(@PgSql BookingStore store,
                                 @SeatReleasedPublisher Publisher<SeatReleased> seatReleased) {
        @SuppressWarnings("JBCT-SEQ-01")
        record sweepHolds(BookingStore store, Publisher<SeatReleased> seatReleased) implements SweepHolds {
            // JBCT pattern: Iteration -- expire held-but-stale rows, publish SeatReleased per freed seat.
            @Override
            public Promise<Response> execute(Request request) {
                return store.expireHolds()
                            .mapError(_ -> SweepError.storeUnavailable())
                            .flatMap(this::releaseAll);
            }

            private Promise<Response> releaseAll(List<SeatRef> seats) {
                return Promise.allOf(seats.stream().map(this::publishRelease).toList()).map(_ -> new Response(seats.size()));
            }

            private Promise<Unit> publishRelease(SeatRef seat) {
                return seatReleased.publish(new SeatReleased(seat.seatId().toString(),
                                                             seat.eventId().toString()));
            }
        }

        return new sweepHolds(store, seatReleased);
    }
}
