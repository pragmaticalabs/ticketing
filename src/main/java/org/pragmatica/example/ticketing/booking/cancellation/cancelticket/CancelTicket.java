package org.pragmatica.example.ticketing.booking.cancellation.cancelticket;

import java.util.UUID;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.http.Http;
import org.pragmatica.aether.resource.http.HttpClient;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.example.ticketing.booking.BookingStore;
import org.pragmatica.example.ticketing.booking.BookingStore.BookingRow;
import org.pragmatica.example.ticketing.booking.BookingStore.ClaimRef;
import org.pragmatica.example.ticketing.booking.BookingStore.ReceiptRef;
import org.pragmatica.example.ticketing.shared.BookingId;
import org.pragmatica.example.ticketing.shared.CustomerId;
import org.pragmatica.example.ticketing.shared.ReceiptId;
import org.pragmatica.example.ticketing.shared.Validation;
import org.pragmatica.example.ticketing.shared.event.SeatReleased;
import org.pragmatica.example.ticketing.shared.event.SeatReleasedPublisher;


/// Use case: cancel a confirmed booking and refund it. Telescope leaf -- system `ticketing` ->
/// subsystem `booking` -> workflow `cancellation` -> use case `cancel-ticket`. One use case, one
/// `Request`/`Response` pair, one `execute` method.
///
/// Recovery is **forward, by re-drive** rather than by compensation: there is no way to un-refund a
/// payment, so instead of undoing work the operation is ordered and guarded so that a caller can
/// simply call it again until it completes. Two rules earn that:
///
///   1. **The refund happens first.** Nothing the customer holds -- ticket, seat, booking -- is
///      touched until the money is back. A refund failure therefore leaves the exact pre-state, and
///      the post-state "seat released but payment kept" is unreachable by construction.
///   2. **The booking row is closed last.** It is the row `ensureNotCancelled` reads, so it is the
///      operation's commit marker. Every earlier step is idempotent (invalidating an invalidated
///      ticket) or guarded (freeing an already-freed seat yields an empty projection), so a failure
///      anywhere leaves the whole cancellation re-drivable from the top; `AlreadyCancelled` is
///      returned only once everything else has already succeeded.
///
/// A re-drive does not refund twice: the completed refund is recorded against the booking's payment
/// and read back as the idempotency key. The one gap this cannot close is a store failure between a
/// successful gateway refund and recording it -- a re-drive then re-issues the refund request, which
/// the provider must dedupe by the booking reference the request carries.
///
/// Its own tail: if the final booking write fails, the seat is already free and the money already
/// returned, but the booking row still reads 'confirmed'. A re-drive closes it; failing that, the
/// orphan reaper in sweep-holds reclaims the reservation by age.
@Slice
public interface CancelTicket {
    record Request(String booking, String customer) {}

    record Response(String booking, String receipt) {}

    // Payment-gateway wire DTOs (plain records; the @Http client serializes/deserializes them as JSON).
    record RefundRequest(String booking) {}

    record RefundResult(String receiptId) {}

    /// Validated cancel target. Both ids are parsed into value objects; the closing `mapError` unwraps
    /// the composite `Result.all` builds around the collected failures, because only a cause declared
    /// in this slice's own package reaches the generated router's error switch (a composite, or a
    /// shared `BookingId.Error`, falls through to HTTP 500 instead of 400).
    record ValidCancel(BookingId booking, CustomerId customer) {
        static Result<ValidCancel> validCancel(Request request) {
            return Result.all(BookingId.bookingId(request.booking()).mapError(CancelError::invalidBooking),
                              CustomerId.customerId(request.customer()).mapError(CancelError::invalidCustomer))
                         .map(ValidCancel::new)
                         .mapError(Validation::firstFailure);
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

    /// Terminal cancel stage: the loaded booking plus the refund receipt, whether that refund was
    /// just performed or read back from a previous attempt.
    record RefundedBooking(LoadedBooking loaded, String receipt) {
        UUID bookingUuid() {
            return loaded.bookingUuid();
        }

        UUID seatUuid() {
            return loaded.seatUuid();
        }

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

    /// The cancellation after the seat has been freed and the booking closed. `version` is the
    /// reservation slot's sequence at the releasing transition, and is PRESENT only when this attempt
    /// actually performed the release. An empty projection means an earlier attempt already freed the
    /// seat; that attempt owns the corresponding fact, so re-driving publishes nothing rather than
    /// inventing a transition that did not happen.
    record ReleasedBooking(RefundedBooking refunded, Option<Long> version) {
        SeatReleased fact(long at) {
            return new SeatReleased(refunded.seatStr(), refunded.eventStr(), at);
        }
    }

    Promise<Response> execute(Request request);

    /// Closed set of cancel failures. Fixed-message refusals are grouped into one enum per HTTP status
    /// so route error-mapping can target a whole status class by that enum's simple name (see
    /// routes.toml); data-carrying refusals stay records.
    sealed interface CancelError extends Cause {
        /// Fixed-message reads that found nothing. Every constant here is an HTTP 404 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 404 must not be added to it, and one
        /// `*EntityMissing*` pattern maps the whole enum.
        enum EntityMissing implements CancelError {
            BOOKING("Booking not found");
            private final String message;
            EntityMissing(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        /// Fixed-message refusals to act on another party's data. Every constant here is an HTTP 403 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 403 must not be added to it, and one
        /// `*AccessRefused*` pattern maps the whole enum.
        enum AccessRefused implements CancelError {
            NOT_OWNER("Booking belongs to another customer");
            private final String message;
            AccessRefused(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        /// Fixed-message refusals by a guard on current state. Every constant here is an HTTP 409 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 409 must not be added to it, and one
        /// `*StateConflict*` pattern maps the whole enum.
        enum StateConflict implements CancelError {
            ALREADY_CANCELLED("Booking is already cancelled");
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
        enum ServiceUnavailable implements CancelError {
            BOOKING_STORE("Booking store is unavailable"),
            PAYMENT_GATEWAY("Refund could not be completed");
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
        record InvalidRequest(String field, String detail) implements CancelError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        static CancelError bookingNotFound() {
            return EntityMissing.BOOKING;
        }

        static CancelError notOwner() {
            return AccessRefused.NOT_OWNER;
        }

        static CancelError alreadyCancelled() {
            return StateConflict.ALREADY_CANCELLED;
        }

        static CancelError refundFailed() {
            return ServiceUnavailable.PAYMENT_GATEWAY;
        }

        static CancelError storeUnavailable() {
            return ServiceUnavailable.BOOKING_STORE;
        }

        static CancelError invalidBooking(Cause cause) {
            return new InvalidRequest("booking", cause.message());
        }

        static CancelError invalidCustomer(Cause cause) {
            return new InvalidRequest("customer", cause.message());
        }
    }

    static CancelTicket cancelTicket(@PgSql BookingStore store,
                                     @Http HttpClient gateway,
                                     @SeatReleasedPublisher Publisher<SeatReleased> seatReleased) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
        record cancelTicket(BookingStore store, HttpClient gateway, Publisher<SeatReleased> seatReleased) implements CancelTicket {
            // JBCT pattern: Sequencer -- load -> ensure cancellable -> refund (idempotent) -> release
            // and close -> publish SeatReleased.
            @Override
            public Promise<Response> execute(Request request) {
                return ValidCancel.validCancel(request)
                                  .async()
                                  .flatMap(this::loadBooking)
                                  .flatMap(this::ensureCancellable)
                                  .flatMap(this::refund)
                                  .flatMap(this::releaseAndClose)
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

            // Refund FIRST, and record it before anything else moves. Nothing the customer holds is
            // taken away until the money is back, so no failure can leave the seat released and the
            // payment kept.
            private Promise<RefundedBooking> refund(LoadedBooking loaded) {
                return store.findRefund(loaded.bookingUuid())
                            .mapError(_ -> CancelError.storeUnavailable())
                            .flatMap(recorded -> resumeOrRefund(loaded, recorded));
            }

            // JBCT pattern: Condition -- a refund already recorded for this booking is replayed from
            // the store, never re-issued to the gateway.
            private Promise<RefundedBooking> resumeOrRefund(LoadedBooking loaded, Option<ReceiptRef> recorded) {
                return recorded.map(receipt -> resumeRefund(loaded, receipt))
                               .or(() -> callRefund(loaded));
            }

            private Promise<RefundedBooking> resumeRefund(LoadedBooking loaded, ReceiptRef receipt) {
                return Promise.success(new RefundedBooking(loaded,
                                                           receipt.receiptId().toString()));
            }

            // Refund the booking at the gateway; a hard failure surfaces as RefundFailed with nothing
            // yet written, so the whole cancellation can simply be re-driven.
            private Promise<RefundedBooking> callRefund(LoadedBooking loaded) {
                return gateway.postJson("/refund",
                                        new RefundRequest(loaded.bookingStr()),
                                        RefundResult.class)
                              .mapError(_ -> CancelError.refundFailed())
                              .flatMap(result -> recordRefund(loaded, result));
            }

            private Promise<RefundedBooking> recordRefund(LoadedBooking loaded, RefundResult result) {
                return ReceiptId.receiptId(result.receiptId())
                                .mapError(_ -> CancelError.refundFailed())
                                .async()
                                .flatMap(receipt -> markRefunded(loaded, receipt));
            }

            private Promise<RefundedBooking> markRefunded(LoadedBooking loaded, ReceiptId receipt) {
                return store.markRefunded(receipt.value().value(),
                                          loaded.bookingUuid())
                            .mapError(_ -> CancelError.storeUnavailable())
                            .map(_ -> new RefundedBooking(loaded,
                                                          receipt.value().value().toString()));
            }

            // JBCT pattern: Sequencer -- invalidate the ticket, free the seat, close the booking LAST.
            // The order IS the recovery mechanism: every step is idempotent or guarded, and the
            // booking row is what `ensureNotCancelled` reads, so a failure at any point leaves the
            // operation re-drivable from the top. Only the final write makes the cancellation
            // terminal, and by then everything else has already succeeded.
            private Promise<ReleasedBooking> releaseAndClose(RefundedBooking refunded) {
                return invalidate(refunded).flatMap(this::releaseSeat)
                                 .flatMap(this::closeBooking);
            }

            // JBCT pattern: Leaf -- invalidate the ticket, carrying the immutable stage forward.
            private Promise<RefundedBooking> invalidate(RefundedBooking refunded) {
                return store.invalidateTicket(refunded.ticketUuid())
                            .mapError(_ -> CancelError.storeUnavailable())
                            .map(_ -> refunded);
            }

            // An empty projection means the seat was already freed by an earlier attempt.
            private Promise<ReleasedBooking> releaseSeat(RefundedBooking refunded) {
                return store.cancelReservationBySeat(refunded.seatUuid())
                            .mapError(_ -> CancelError.storeUnavailable())
                            .map(claim -> new ReleasedBooking(refunded,
                                                              claim.map(ClaimRef::version)));
            }

            private Promise<ReleasedBooking> closeBooking(ReleasedBooking released) {
                return store.cancelBooking(released.refunded().bookingUuid())
                            .mapError(_ -> CancelError.storeUnavailable())
                            .flatMap(cancelled -> cancelled.async(CancelError.alreadyCancelled()))
                            .map(_ -> released);
            }

            // FER, for the same reason the buy saga publishes best-effort: by this point the refund
            // and every write have succeeded and `AlreadyCancelled` would reject a re-drive, so a
            // publish failure must not turn a completed cancellation into a reported one. Mechanism:
            // a single attempt -- a lost SeatReleased leaves availability stale for that seat until
            // the next fact about it.
            private Promise<Response> publishReleased(ReleasedBooking released) {
                return released.version()
                               .fold(() -> Promise.UNIT,
                                     version -> announce(released, version))
                               .map(_ -> new Response(released.refunded().bookingStr(),
                                                      released.refunded().receipt()));
            }

            /// Only reached when THIS attempt performed the release, so the fact carries the version of
            /// the transition that actually freed the seat rather than a re-drive's stale reading.
            private Promise<Unit> announce(ReleasedBooking released, long version) {
                return seatReleased.publish(released.fact(version))
                                   .recover(_ -> Unit.unit());
            }
        }

        return new cancelTicket(store, gateway, seatReleased);
    }
}
