package org.pragmatica.example.ticketing.eventmanagement.capacity.releaseseat;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.shared.SeatId;


/// Use case (BER, inverse of block-seat): release a blocked seat back to inventory.
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `capacity` -> use
/// case `release-seat`. Guarded transition: releases only a seat currently 'blocked'.
@Slice
public interface ReleaseSeat {
    record Request(String seat) {}

    record Response(String seat) {}

    Promise<Response> execute(Request request);

    sealed interface ReleaseSeatError extends Cause {
        /// Fixed-message refusals by a guard on current state. Every constant here is an HTTP 409 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 409 must not be added to it, and one
        /// `*StateConflict*` pattern maps the whole enum.
        enum StateConflict implements ReleaseSeatError {
            SEAT_NOT_BLOCKED("Seat is not blocked");
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
        enum ServiceUnavailable implements ReleaseSeatError {
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
        record InvalidRequest(String field, String detail) implements ReleaseSeatError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        static ReleaseSeatError seatNotBlocked() {
            return StateConflict.SEAT_NOT_BLOCKED;
        }

        static ReleaseSeatError storeUnavailable() {
            return ServiceUnavailable.EVENT_MANAGEMENT_STORE;
        }

        static ReleaseSeatError invalidSeat(Cause cause) {
            return new InvalidRequest("seat", cause.message());
        }
    }

    static ReleaseSeat releaseSeat(@PgSql EventStore store) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
        record releaseSeat(EventStore store) implements ReleaseSeat {
            // JBCT pattern: Sequencer -- validate -> guarded release update.
            @Override
            public Promise<Response> execute(Request request) {
                return SeatId.seatId(request.seat())
                             .mapError(ReleaseSeatError::invalidSeat)
                             .async()
                             .flatMap(this::doRelease);
            }

            private Promise<Response> doRelease(SeatId seatId) {
                var uuid = seatId.value().value();

                return store.releaseSeat(uuid)
                            .mapError(_ -> ReleaseSeatError.storeUnavailable())
                            .flatMap(found -> found.async(ReleaseSeatError.seatNotBlocked()))
                            .map(_ -> new Response(uuid.toString()));
            }
        }

        return new releaseSeat(store);
    }
}
