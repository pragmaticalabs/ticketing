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
    @SuppressWarnings("JBCT-VO-01")
    record Request(String seat) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String seat, String state) {}

    sealed interface AvailabilityError extends Cause {
        record StoreUnavailable() implements AvailabilityError {
            @Override
            public String message() {
                return "Availability store is unavailable";
            }
        }

        static AvailabilityError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static SeatStatus seatStatus(@PgSql SeatStatusStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record seatStatus(SeatStatusStore store) implements SeatStatus {
            // JBCT pattern: Sequencer -- validate -> read -> respond.
            @Override
            public Promise<Response> execute(Request request) {
                return SeatId.seatId(request.seat())
                             .async()
                             .flatMap(this::lookup);
            }

            private Promise<Response> lookup(SeatId seatId) {
                var seat = seatId.value().value().toString();

                return store.findStatus(seatId.value().value())
                            .mapError(_ -> AvailabilityError.storeUnavailable())
                            .map(this::statusOf)
                            .map(state -> new Response(seat, state));
            }

            // A missing row means the seat was never sold/held -- default to available.
            private String statusOf(Option<StatusRow> found) {
                return found.map(StatusRow::state)
                            .or(SeatState.AVAILABLE.dbValue());
            }
        }

        return new seatStatus(store);
    }
}
