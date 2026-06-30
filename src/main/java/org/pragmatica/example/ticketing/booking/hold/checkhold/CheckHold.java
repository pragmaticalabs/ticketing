package org.pragmatica.example.ticketing.booking.hold.checkhold;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.booking.BookingStore;
import org.pragmatica.example.ticketing.booking.BookingStore.HoldRow;
import org.pragmatica.example.ticketing.shared.SeatId;


/// Use case: report the decay state of a seat's hold (FRESH / STALE / EXPIRED / NONE). Telescope
/// leaf -- system `ticketing` -> subsystem `booking` -> workflow `hold` -> use case `check-hold`.
/// One use case, one `Request`/`Response` pair, one `execute` method.
///
/// Recovery class: **FER** -- the hold decays with time; the read maps the persisted state plus the
/// time-as-decay flags to a single label.
@Slice
public interface CheckHold {
    @SuppressWarnings("JBCT-VO-01")
    record Request(String seat) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String seat, String state) {}

    /// Closed set of check failures. Each is a distinct record so route error-mapping can target it
    /// by simple name (see routes.toml).
    sealed interface CheckError extends Cause {
        record StoreUnavailable() implements CheckError {
            @Override
            public String message() {
                return "Booking store is unavailable";
            }
        }

        static CheckError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static CheckHold checkHold(@PgSql BookingStore store) {
        record checkHold(BookingStore store) implements CheckHold {
            // JBCT pattern: Sequencer -- validate -> read the hold's decay snapshot.
            @Override
            public Promise<Response> execute(Request request) {
                return SeatId.seatId(request.seat())
                             .async()
                             .flatMap(this::loadHoldState);
            }

            private Promise<Response> loadHoldState(SeatId seatId) {
                var seatStr = seatId.value().value().toString();

                return store.holdDecay(seatId.value().value())
                            .mapError(_ -> CheckError.storeUnavailable())
                            .map(found -> new Response(seatStr,
                                                       decayState(found)));
            }

            // JBCT pattern: Condition (pure) -- map the persisted state + decay flags to a label.
            private String decayState(Option<HoldRow> found) {
                return found.map(this::classify)
                            .or("NONE");
            }

            private String classify(HoldRow row) {
                return row.expired()
                       ? "EXPIRED"
                       : staleOrFresh(row);
            }

            private String staleOrFresh(HoldRow row) {
                return row.stale()
                       ? "STALE"
                       : "FRESH";
            }
        }

        return new checkHold(store);
    }
}
