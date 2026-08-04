package org.pragmatica.example.ticketing.booking.hold.acquirehold;

import java.util.UUID;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.example.ticketing.booking.BookingStore;
import org.pragmatica.example.ticketing.eventmanagement.capacity.seatsellability.SeatSellability;
import org.pragmatica.example.ticketing.shared.CustomerId;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.SeatId;
import org.pragmatica.example.ticketing.shared.Validation;


/// Use case: claim a seat with a decaying 15-minute hold (FER). Telescope leaf -- system
/// `ticketing` -> subsystem `booking` -> workflow `hold` -> use case `acquire-hold`. One use case,
/// one `Request`/`Response` pair, one `execute` method.
///
/// Recovery class: **design-out** -- the hold is the same single guarded seat claim used by the buy
/// saga (state 'held'); the loser of a contended seat fast-fails with SeatUnavailable. A customer
/// re-acquiring their own live hold is admitted by the guard and simply refreshes the TTL, so the
/// hold can later be converted into a purchase instead of blocking it.
///
/// The returned `reservation` is the claim identity the database generated for this claim; it is the
/// handle a later confirm or release must present, and a competing reclaim rotates it away.
@Slice
public interface AcquireHold {
    record Request(String customer, String event, String seat) {}

    record Response(String reservation, String state) {}

    /// Validated hold-acquire target. Every id is parsed into a value object; the closing `mapError`
    /// unwraps the composite `Result.all` builds around the collected failures, because only a cause
    /// declared in this slice's own package reaches the generated router's error switch (a composite,
    /// or a shared `SeatId.Error`, falls through to HTTP 500 instead of 400).
    record ValidAcquire(CustomerId customer, EventId event, SeatId seat) {
        static Result<ValidAcquire> validAcquire(Request request) {
            return Result.all(CustomerId.customerId(request.customer()).mapError(AcquireError::invalidCustomer),
                              EventId.eventId(request.event()).mapError(AcquireError::invalidEvent),
                              SeatId.seatId(request.seat()).mapError(AcquireError::invalidSeat))
                         .map(ValidAcquire::new)
                         .mapError(Validation::firstFailure);
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

        String seatStr() {
            return seat.value()
                       .value()
                       .toString();
        }
    }

    Promise<Response> execute(Request request);

    /// Closed set of acquire failures. Fixed-message refusals are grouped into one enum per HTTP status
    /// so route error-mapping can target a whole status class by that enum's simple name (see
    /// routes.toml); data-carrying refusals stay records.
    sealed interface AcquireError extends Cause {
        /// Fixed-message refusals by a guard on current state. Every constant here is an HTTP 409 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 409 must not be added to it, and one
        /// `*StateConflict*` pattern maps the whole enum.
        enum StateConflict implements AcquireError {
            SEAT_UNAVAILABLE("Seat is no longer available"),
            /// The operator has withheld this seat from sale (blocked or withdrawn), or event-management
            /// could not answer. Distinct from [#SEAT_UNAVAILABLE], which means another customer holds or
            /// owns the seat: this one is not resolved by waiting for a hold to lapse.
            SEAT_NOT_SELLABLE("Seat is not available for sale");
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
        enum ServiceUnavailable implements AcquireError {
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
        record InvalidRequest(String field, String detail) implements AcquireError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        static AcquireError seatUnavailable() {
            return StateConflict.SEAT_UNAVAILABLE;
        }

        static AcquireError storeUnavailable() {
            return ServiceUnavailable.BOOKING_STORE;
        }

        static AcquireError seatNotSellable() {
            return StateConflict.SEAT_NOT_SELLABLE;
        }

        static AcquireError invalidCustomer(Cause cause) {
            return new InvalidRequest("customer", cause.message());
        }

        static AcquireError invalidEvent(Cause cause) {
            return new InvalidRequest("event", cause.message());
        }

        static AcquireError invalidSeat(Cause cause) {
            return new InvalidRequest("seat", cause.message());
        }
    }

    static AcquireHold acquireHold(@PgSql BookingStore store, SeatSellability seatSellability) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
        record acquireHold(BookingStore store, SeatSellability seatSellability) implements AcquireHold {
            // JBCT pattern: Sequencer -- validate -> gate on authoritative seat state -> design-out
            // hold claim (state 'held' with a TTL).
            @Override
            public Promise<Response> execute(Request request) {
                return ValidAcquire.validAcquire(request)
                                   .async()
                                   .flatMap(this::ensureSellable)
                                   .flatMap(this::claimHold);
            }

            // Synchronous cross-slice read: the `seats` table is owned by event-management, so a
            // blocked or withdrawn seat is only visible through its slice. Without this gate
            // `block-seat` is inert -- it writes a column no hold path consults.
            private Promise<ValidAcquire> ensureSellable(ValidAcquire valid) {
                return seatSellability.execute(new SeatSellability.Request(valid.seatStr()))
                                      .mapError(_ -> AcquireError.seatNotSellable())
                                      .flatMap(sellability -> sellabilityGate(valid, sellability));
            }

            // JBCT pattern: Condition -- route on sellability, no transformation.
            private Promise<ValidAcquire> sellabilityGate(ValidAcquire valid, SeatSellability.Response sellability) {
                return sellability.sellable()
                       ? Promise.success(valid)
                       : AcquireError.seatNotSellable().promise();
            }

            private Promise<Response> claimHold(ValidAcquire valid) {
                return store.claimSeat(valid.seatUuid(),
                                       valid.eventUuid(),
                                       valid.customerUuid())
                            .mapError(_ -> AcquireError.storeUnavailable())
                            .flatMap(claimed -> claimed.async(AcquireError.seatUnavailable()))
                            .map(claim -> new Response(claim.claimId().toString(),
                                                       "FRESH"));
            }
        }

        return new acquireHold(store, seatSellability);
    }
}
