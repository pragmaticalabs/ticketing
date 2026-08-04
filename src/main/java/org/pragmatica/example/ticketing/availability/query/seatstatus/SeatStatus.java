package org.pragmatica.example.ticketing.availability.query.seatstatus;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.availability.query.seatstatus.SeatStatusStore.StatusRow;
import org.pragmatica.example.ticketing.shared.SeatId;
import org.pragmatica.example.ticketing.shared.SeatState;


/// Use case: read the latest status of a single seat from the availability projection.
/// Telescope leaf — system `ticketing` → subsystem `availability` → workflow `query` → use case
/// `seat-status`. One use case, one `Request`/`Response` pair, one `execute` method. A seat with no
/// projection row was never sold/held, so it reads as available.
@Slice
public interface SeatStatus {
    record Request(String seat) {}

    record Response(String seat, String state) {}

    sealed interface AvailabilityError extends Cause {
        record StoreUnavailable() implements AvailabilityError {
            @Override
            public String message() {
                return "Availability store is unavailable";
            }
        }

        /// Client-facing validation refusal (HTTP 400): a request field could not be parsed into its
        /// domain type. Declared in this slice's own hierarchy instead of letting the shared
        /// value-object cause through, because the slice processor builds the router's error switch
        /// from the `Cause` types in this package alone -- a shared cause arrives unmatched and falls
        /// through to HTTP 500. Data-carrying, so the response names the offending field and keeps the
        /// original reason.
        record InvalidRequest(String field, String detail) implements AvailabilityError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        static AvailabilityError storeUnavailable() {
            return new StoreUnavailable();
        }

        static AvailabilityError invalidSeat(Cause cause) {
            return new InvalidRequest("seat", cause.message());
        }
    }

    @SeatStatusCache
    Promise<Response> execute(Request request);

    static SeatStatus seatStatus(@PgSql SeatStatusStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record seatStatus(SeatStatusStore store) implements SeatStatus {
            // JBCT pattern: Sequencer -- validate -> read -> respond.
            @Override
            public Promise<Response> execute(Request request) {
                return SeatId.seatId(request.seat())
                             .mapError(AvailabilityError::invalidSeat)
                             .async()
                             .flatMap(this::lookup);
            }

            private Promise<Response> lookup(SeatId seatId) {
                var seat = seatId.value().value().toString();

                return store.findStatus(seatId.value().value())
                            .mapError(_ -> AvailabilityError.storeUnavailable())
                            .map(this::statusOf)
                            .map(state -> new Response(seat,
                                                       state.dbValue()));
            }

            // A missing row means the seat was never sold/held -- default to available.
            private SeatState statusOf(Option<StatusRow> found) {
                return found.map(StatusRow::state)
                            .or(SeatState.AVAILABLE);
            }
        }

        return new seatStatus(store);
    }
}
