package org.pragmatica.example.ticketing.booking.cancellation.cancelticket;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.http.Http;
import org.pragmatica.aether.resource.http.HttpClient;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.example.ticketing.booking.BookingStore;
import org.pragmatica.example.ticketing.booking.BookingStore.BookingRow;
import org.pragmatica.example.ticketing.shared.BookingId;
import org.pragmatica.example.ticketing.shared.CustomerId;
import org.pragmatica.example.ticketing.shared.event.SeatReleased;
import org.pragmatica.example.ticketing.shared.event.SeatReleasedPublisher;

import java.util.UUID;


/// Use case: cancel a confirmed booking and refund it. Telescope leaf -- system `ticketing` ->
/// subsystem `booking` -> workflow `cancellation` -> use case `cancel-ticket`. One use case, one
/// `Request`/`Response` pair, one `execute` method.
///
/// Recovery class: **BER** -- ownership and cancellable state are checked first, the booking row and
/// reservation are cancelled, the payment is refunded at the gateway, the ticket is invalidated, and
/// the freed seat is published as a `SeatReleased` fact.
@Slice
public interface CancelTicket {
    @SuppressWarnings("JBCT-VO-01")
    record Request(String booking, String customer) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String booking, String receipt) {}

    // Payment-gateway wire DTOs (plain records; the @Http client serializes/deserializes them as JSON).
    @SuppressWarnings("JBCT-VO-01")
    record RefundRequest(String booking) {}

    @SuppressWarnings("JBCT-VO-01")
    record RefundResult(String receiptId) {}

    /// Validated cancel target.
    record ValidCancel(BookingId booking, CustomerId customer) {
        static Result<ValidCancel> validCancel(Request request) {
            return Result.all(BookingId.bookingId(request.booking()),
                              CustomerId.customerId(request.customer()))
                         .map(ValidCancel::new);
        }

        UUID bookingUuid() {
            return booking.value()
                          .value();
        }

        UUID customerUuid() {
            return customer.value()
                           .value();
        }

        String bookingStr() {
            return booking.value()
                          .value()
                          .toString();
        }
    }

    /// Growing-context stage: the validated cancel plus the loaded booking row.
    @SuppressWarnings("JBCT-VO-01")
    record LoadedBooking(ValidCancel valid, BookingRow booking) {
        UUID bookingUuid() {
            return valid.bookingUuid();
        }

        String bookingStr() {
            return valid.bookingStr();
        }

        UUID seatUuid() {
            return booking.seatId();
        }

        String seatStr() {
            return booking.seatId()
                          .toString();
        }

        String eventStr() {
            return booking.eventId()
                          .toString();
        }

        UUID ticketUuid() {
            return booking.ticketId();
        }
    }

    /// Terminal cancel stage: the loaded booking plus the refund receipt.
    @SuppressWarnings("JBCT-VO-01")
    record RefundedBooking(LoadedBooking loaded, String receipt) {
        UUID ticketUuid() {
            return loaded.ticketUuid();
        }

        String seatStr() {
            return loaded.seatStr();
        }

        String eventStr() {
            return loaded.eventStr();
        }

        String bookingStr() {
            return loaded.bookingStr();
        }
    }

    /// Closed set of cancel failures. Each is a distinct record so route error-mapping can target it
    /// by simple name (see routes.toml).
    sealed interface CancelError extends Cause {
        record BookingNotFound() implements CancelError {
            @Override
            public String message() {
                return "Booking not found";
            }
        }

        record NotOwner() implements CancelError {
            @Override
            public String message() {
                return "Booking belongs to another customer";
            }
        }

        record AlreadyCancelled() implements CancelError {
            @Override
            public String message() {
                return "Booking is already cancelled";
            }
        }

        record RefundFailed() implements CancelError {
            @Override
            public String message() {
                return "Refund could not be completed";
            }
        }

        record StoreUnavailable() implements CancelError {
            @Override
            public String message() {
                return "Booking store is unavailable";
            }
        }

        static CancelError bookingNotFound() {
            return new BookingNotFound();
        }

        static CancelError notOwner() {
            return new NotOwner();
        }

        static CancelError alreadyCancelled() {
            return new AlreadyCancelled();
        }

        static CancelError refundFailed() {
            return new RefundFailed();
        }

        static CancelError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static CancelTicket cancelTicket(@PgSql BookingStore store,
                                     @Http HttpClient gateway,
                                     @SeatReleasedPublisher Publisher<SeatReleased> seatReleased) {
        @SuppressWarnings("JBCT-SEQ-01")
        record cancelTicket(BookingStore store, HttpClient gateway, Publisher<SeatReleased> seatReleased) implements CancelTicket {
            // JBCT pattern: Sequencer -- load -> ensure cancellable -> cancel + refund -> invalidate
            // -> publish SeatReleased. Recovery class: BER.
            @Override
            public Promise<Response> execute(Request request) {
                return ValidCancel.validCancel(request)
                                  .async()
                                  .flatMap(this::loadBooking)
                                  .flatMap(this::ensureCancellable)
                                  .flatMap(this::cancelAndRefund)
                                  .flatMap(this::invalidate)
                                  .flatMap(this::publishReleased);
            }

            private Promise<LoadedBooking> loadBooking(ValidCancel valid) {
                return store.findBooking(valid.bookingUuid())
                            .mapError(_ -> CancelError.storeUnavailable())
                            .flatMap(found -> found.async(CancelError.bookingNotFound()))
                            .map(row -> new LoadedBooking(valid, row));
            }

            // JBCT pattern: Condition (pure) -- ownership first, then cancellable state.
            private Promise<LoadedBooking> ensureCancellable(LoadedBooking loaded) {
                return loaded.booking()
                             .customerId()
                             .equals(loaded.valid().customerUuid())
                       ? ensureNotCancelled(loaded)
                       : CancelError.notOwner().promise();
            }

            private Promise<LoadedBooking> ensureNotCancelled(LoadedBooking loaded) {
                return loaded.booking()
                             .status()
                             .equals("cancelled")
                       ? CancelError.alreadyCancelled().promise()
                       : Promise.success(loaded);
            }

            // JBCT pattern: Sequencer -- cancel booking row, free the reservation, then refund.
            private Promise<RefundedBooking> cancelAndRefund(LoadedBooking loaded) {
                return cancelBookingRow(loaded).flatMap(this::cancelReservation)
                                       .flatMap(this::refund);
            }

            private Promise<LoadedBooking> cancelBookingRow(LoadedBooking loaded) {
                return store.cancelBooking(loaded.bookingUuid())
                            .mapError(_ -> CancelError.storeUnavailable())
                            .flatMap(cancelled -> cancelled.async(CancelError.alreadyCancelled()))
                            .map(_ -> loaded);
            }

            private Promise<LoadedBooking> cancelReservation(LoadedBooking loaded) {
                return store.cancelReservationBySeat(loaded.seatUuid())
                            .mapError(_ -> CancelError.storeUnavailable())
                            .map(_ -> loaded);
            }

            // Refund the booking at the gateway; a hard failure surfaces as RefundFailed.
            private Promise<RefundedBooking> refund(LoadedBooking loaded) {
                return gateway.postJson("/refund",
                                        new RefundRequest(loaded.bookingStr()),
                                        RefundResult.class).mapError(_ -> CancelError.refundFailed())
                                       .map(result -> new RefundedBooking(loaded,
                                                                          result.receiptId()));
            }

            // JBCT pattern: Leaf -- invalidate the ticket, carrying the immutable stage forward.
            private Promise<RefundedBooking> invalidate(RefundedBooking refunded) {
                return store.invalidateTicket(refunded.ticketUuid())
                            .mapError(_ -> CancelError.storeUnavailable())
                            .map(_ -> refunded);
            }

            private Promise<Response> publishReleased(RefundedBooking refunded) {
                return seatReleased.publish(new SeatReleased(refunded.seatStr(),
                                                             refunded.eventStr()))
                                   .map(_ -> new Response(refunded.bookingStr(),
                                                          refunded.receipt()));
            }
        }

        return new cancelTicket(store, gateway, seatReleased);
    }
}
