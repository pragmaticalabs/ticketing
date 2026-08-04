package org.pragmatica.example.ticketing.booking.hold.checkhold;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.booking.BookingStore;
import org.pragmatica.example.ticketing.booking.BookingStore.HoldRow;
import org.pragmatica.example.ticketing.shared.SeatId;


/// Use case: report the decay state of a seat's hold (FRESH / STALE / EXPIRED / SOLD / NONE).
/// Telescope leaf -- system `ticketing` -> subsystem `booking` -> workflow `hold` -> use case
/// `check-hold`. One use case, one `Request`/`Response` pair, one `execute` method.
///
/// Recovery class: **FER** -- the hold decays with time. The label is decided by the persisted state
/// first and only then by the time-as-decay flags, because those flags describe a live hold and
/// nothing else: a sold seat reads SOLD, a cancelled reservation reads NONE however much TTL it
/// still carries, and a seat with no reservation at all reads NONE.
@Slice
public interface CheckHold {
    record Request(String seat) {}

    record Response(String seat, String state) {}

    Promise<Response> execute(Request request);

    /// Closed set of check failures. Fixed-message refusals are grouped into one enum per HTTP status
    /// so route error-mapping can target a whole status class by that enum's simple name (see
    /// routes.toml); data-carrying refusals stay records.
    sealed interface CheckError extends Cause {
        /// Fixed-message failures of a dependency this slice calls. Every constant here is an HTTP 503 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 503 must not be added to it, and one
        /// `*ServiceUnavailable*` pattern maps the whole enum.
        enum ServiceUnavailable implements CheckError {
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

        /// Client-facing validation refusal (HTTP 400): a request field could not be parsed into its
        /// domain type. Declared in this slice's own hierarchy instead of letting the shared
        /// value-object cause through, because the slice processor builds the router's error switch
        /// from the `Cause` types in this package alone -- a shared cause arrives unmatched and falls
        /// through to HTTP 500. Data-carrying, so the response names the offending field and keeps the
        /// original reason.
        record InvalidRequest(String field, String detail) implements CheckError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        static CheckError storeUnavailable() {
            return ServiceUnavailable.BOOKING_STORE;
        }

        static CheckError invalidSeat(Cause cause) {
            return new InvalidRequest("seat", cause.message());
        }
    }

    static CheckHold checkHold(@PgSql BookingStore store) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings("JBCT-ORD-01")
        record checkHold(BookingStore store) implements CheckHold {
            // JBCT pattern: Sequencer -- validate -> read the hold's decay snapshot.
            @Override
            public Promise<Response> execute(Request request) {
                return SeatId.seatId(request.seat())
                             .mapError(CheckError::invalidSeat)
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

            // The decay flags describe a live hold and nothing else, so the persisted state decides
            // first: a sold seat has no hold to decay (and no expiry at all), and a cancelled or
            // expired reservation leaves the seat free regardless of the TTL it happens to carry.
            private String classify(HoldRow row) {
                return switch (row.state()) {
                    case "held" -> decayLabel(row);
                    case "confirmed" -> "SOLD";
                    case "expired" -> "EXPIRED";
                    default -> "NONE";
                };
            }

            private String decayLabel(HoldRow row) {
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
