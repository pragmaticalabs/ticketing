package org.pragmatica.example.ticketing.booking.hold.acquirehold;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.example.ticketing.booking.BookingStore;
import org.pragmatica.example.ticketing.shared.CustomerId;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.SeatId;

import java.util.UUID;


/// Use case: claim a seat with a decaying 15-minute hold (FER). Telescope leaf -- system
/// `ticketing` -> subsystem `booking` -> workflow `hold` -> use case `acquire-hold`. One use case,
/// one `Request`/`Response` pair, one `execute` method.
///
/// Recovery class: **design-out** -- the hold is the same single guarded seat claim used by the buy
/// saga (state 'held'); the loser of a contended seat fast-fails with SeatUnavailable.
@Slice
public interface AcquireHold {
    @SuppressWarnings("JBCT-VO-01")
    record Request(String customer, String event, String seat) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String reservation, String state) {}

    /// Validated hold-acquire target.
    record ValidAcquire(CustomerId customer, EventId event, SeatId seat) {
        static Result<ValidAcquire> validAcquire(Request request) {
            return Result.all(CustomerId.customerId(request.customer()),
                              EventId.eventId(request.event()),
                              SeatId.seatId(request.seat()))
                         .map(ValidAcquire::new);
        }

        UUID customerUuid() {
            return customer.value()
                           .value();
        }

        UUID eventUuid() {
            return event.value()
                        .value();
        }

        UUID seatUuid() {
            return seat.value()
                       .value();
        }
    }

    /// Closed set of acquire failures. Each is a distinct record so route error-mapping can target it
    /// by simple name (see routes.toml).
    sealed interface AcquireError extends Cause {
        record SeatUnavailable() implements AcquireError {
            @Override
            public String message() {
                return "Seat is no longer available";
            }
        }

        record StoreUnavailable() implements AcquireError {
            @Override
            public String message() {
                return "Booking store is unavailable";
            }
        }

        static AcquireError seatUnavailable() {
            return new SeatUnavailable();
        }

        static AcquireError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static AcquireHold acquireHold(@PgSql BookingStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record acquireHold(BookingStore store) implements AcquireHold {
            // JBCT pattern: Sequencer -- validate -> design-out hold claim (state 'held' with a TTL).
            @Override
            public Promise<Response> execute(Request request) {
                return ValidAcquire.validAcquire(request)
                                   .async()
                                   .flatMap(this::claimHold);
            }

            private Promise<Response> claimHold(ValidAcquire valid) {
                var reservationId = UUID.randomUUID();

                return store.claimSeat(reservationId,
                                       valid.seatUuid(),
                                       valid.eventUuid(),
                                       valid.customerUuid()).mapError(_ -> AcquireError.storeUnavailable())
                                      .flatMap(claimed -> claimed.async(AcquireError.seatUnavailable()))
                                      .map(_ -> new Response(reservationId.toString(),
                                                             "FRESH"));
            }
        }

        return new acquireHold(store);
    }
}
