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
    @SuppressWarnings("JBCT-VO-01")
    record Request(String seat) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String seat) {}

    sealed interface ReleaseSeatError extends Cause {
        record SeatNotBlocked() implements ReleaseSeatError {
            @Override
            public String message() {
                return "Seat is not blocked";
            }
        }

        record StoreUnavailable() implements ReleaseSeatError {
            @Override
            public String message() {
                return "Event management store is unavailable";
            }
        }

        static ReleaseSeatError seatNotBlocked() {
            return new SeatNotBlocked();
        }

        static ReleaseSeatError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static ReleaseSeat releaseSeat(@PgSql EventStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record releaseSeat(EventStore store) implements ReleaseSeat {
            // JBCT pattern: Sequencer -- validate -> guarded release update.
            @Override
            public Promise<Response> execute(Request request) {
                return SeatId.seatId(request.seat())
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
