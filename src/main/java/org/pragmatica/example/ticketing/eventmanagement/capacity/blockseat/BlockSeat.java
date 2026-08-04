package org.pragmatica.example.ticketing.eventmanagement.capacity.blockseat;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.shared.SeatId;


/// Use case (BER, inverse of release-seat): block an available seat.
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `capacity` -> use
/// case `block-seat`. Guarded transition: blocks only a seat currently 'available'.
@Slice
public interface BlockSeat {
    record Request(String seat) {}

    record Response(String seat) {}

    Promise<Response> execute(Request request);

    sealed interface BlockSeatError extends Cause {
        /// Fixed-message refusals by a guard on current state. Every constant here is an HTTP 409 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 409 must not be added to it, and one
        /// `*StateConflict*` pattern maps the whole enum.
        enum StateConflict implements BlockSeatError {
            SEAT_UNAVAILABLE("Seat is not available to block");
            private final String message;
            StateConflict(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        /// Fixed-message failures of a dependency this slice calls. Every constant here is an HTTP 503 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 503 must not be added to it, and one
        /// `*ServiceUnavailable*` pattern maps the whole enum.
        enum ServiceUnavailable implements BlockSeatError {
            EVENT_MANAGEMENT_STORE("Event management store is unavailable");
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
        record InvalidRequest(String field, String detail) implements BlockSeatError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        static BlockSeatError seatUnavailable() {
            return StateConflict.SEAT_UNAVAILABLE;
        }

        static BlockSeatError storeUnavailable() {
            return ServiceUnavailable.EVENT_MANAGEMENT_STORE;
        }

        static BlockSeatError invalidSeat(Cause cause) {
            return new InvalidRequest("seat", cause.message());
        }
    }

    static BlockSeat blockSeat(@PgSql EventStore store) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
        record blockSeat(EventStore store) implements BlockSeat {
            // JBCT pattern: Sequencer -- validate -> guarded block update.
            @Override
            public Promise<Response> execute(Request request) {
                return SeatId.seatId(request.seat())
                             .mapError(BlockSeatError::invalidSeat)
                             .async()
                             .flatMap(this::doBlock);
            }

            private Promise<Response> doBlock(SeatId seatId) {
                var uuid = seatId.value().value();

                return store.blockSeat(uuid)
                            .mapError(_ -> BlockSeatError.storeUnavailable())
                            .flatMap(found -> found.async(BlockSeatError.seatUnavailable()))
                            .map(_ -> new Response(uuid.toString()));
            }
        }

        return new blockSeat(store);
    }
}
