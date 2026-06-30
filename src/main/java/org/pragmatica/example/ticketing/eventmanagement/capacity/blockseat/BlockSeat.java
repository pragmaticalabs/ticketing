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
    @SuppressWarnings("JBCT-VO-01")
    record Request(String seat) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String seat) {}

    sealed interface BlockSeatError extends Cause {
        record SeatUnavailable() implements BlockSeatError {
            @Override
            public String message() {
                return "Seat is not available to block";
            }
        }

        record StoreUnavailable() implements BlockSeatError {
            @Override
            public String message() {
                return "Event management store is unavailable";
            }
        }

        static BlockSeatError seatUnavailable() {
            return new SeatUnavailable();
        }

        static BlockSeatError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static BlockSeat blockSeat(@PgSql EventStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record blockSeat(EventStore store) implements BlockSeat {
            // JBCT pattern: Sequencer -- validate -> guarded block update.
            @Override
            public Promise<Response> execute(Request request) {
                return SeatId.seatId(request.seat())
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
