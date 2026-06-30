package org.pragmatica.example.ticketing.booking.purchase.buyticket;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.http.Http;
import org.pragmatica.aether.resource.http.HttpClient;
import org.pragmatica.aether.resource.notification.Notification;
import org.pragmatica.aether.resource.notification.NotificationBody;
import org.pragmatica.aether.resource.notification.NotificationSender;
import org.pragmatica.aether.resource.notification.Notify;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.example.ticketing.booking.BookingStore;
import org.pragmatica.example.ticketing.eventmanagement.sales.salestatus.SaleStatus;
import org.pragmatica.example.ticketing.pricing.quoting.quoteprice.QuotePrice;
import org.pragmatica.example.ticketing.shared.BookingId;
import org.pragmatica.example.ticketing.shared.CustomerId;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.PriceTier;
import org.pragmatica.example.ticketing.shared.ReceiptId;
import org.pragmatica.example.ticketing.shared.SeatId;
import org.pragmatica.example.ticketing.shared.TicketId;
import org.pragmatica.example.ticketing.shared.event.SeatSold;
import org.pragmatica.example.ticketing.shared.event.SeatSoldPublisher;

import java.util.List;
import java.util.UUID;


/// Use case: buy a ticket for a seat (the BER-saga centerpiece). Telescope leaf -- system
/// `ticketing` -> subsystem `booking` -> workflow `purchase` -> use case `buy-ticket`. One use
/// case, one `Request`/`Response` pair, one `execute` method.
///
/// Recovery classes:
///   - **design-out**: the seat claim is a single guarded `INSERT ... ON CONFLICT ... RETURNING`;
///     the loser of a contended seat fast-fails with SeatUnavailable -- no lock, no race.
///   - **BER** (backward error recovery / saga): a payment failure after the seat is claimed
///     releases the reservation; a failure after the payment is authorized voids the authorization
///     and releases the reservation. Compensation lives in dedicated private helpers that re-raise
///     the original typed failure.
///   - **FER** (forward error recovery): the confirmation notification is best-effort -- a notify
///     failure never fails the buy.
///
/// Sale status and the authoritative price are read **synchronously** from the event-management and
/// pricing slices (injected as plain factory parameters); the payment gateway is an `@Http` resource
/// and notifications an `@Notify` resource.
@Slice
public interface BuyTicket {
    @SuppressWarnings("JBCT-VO-01")
    record Request(String customer, String event, String seat, String tier) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String booking, String ticket, String seat, String receipt, long amountMinor, String currency) {}

    // Payment-gateway wire DTOs (plain records; the @Http client serializes/deserializes them as JSON).
    @SuppressWarnings("JBCT-VO-01")
    record AuthRequest(long amountMinor, String currency, String customer) {}

    @SuppressWarnings("JBCT-VO-01")
    record AuthResult(boolean approved, String receiptId) {}

    @SuppressWarnings("JBCT-VO-01")
    record VoidRequest(String receiptId) {}

    @SuppressWarnings("JBCT-VO-01")
    record VoidResult(String status) {}

    /// Validated buy target. Raw request fields are parsed into value objects; all failures surface
    /// together via Result.all.
    record ValidBuy(CustomerId customer, EventId event, SeatId seat, PriceTier tier) {
        static Result<ValidBuy> validBuy(Request request) {
            return Result.all(CustomerId.customerId(request.customer()),
                              EventId.eventId(request.event()),
                              SeatId.seatId(request.seat()),
                              PriceTier.priceTier(request.tier()))
                         .map(ValidBuy::new);
        }

        String eventStr() {
            return event.value()
                        .value()
                        .toString();
        }

        String seatStr() {
            return seat.value()
                       .value()
                       .toString();
        }

        String customerStr() {
            return customer.value()
                           .value()
                           .toString();
        }

        String tierStr() {
            return tier.name();
        }

        UUID eventUuid() {
            return event.value()
                        .value();
        }

        UUID seatUuid() {
            return seat.value()
                       .value();
        }

        UUID customerUuid() {
            return customer.value()
                           .value();
        }
    }

    /// Growing-context stage: validated buy plus the authoritative price.
    @SuppressWarnings("JBCT-VO-01")
    record PricedBuy(ValidBuy buy, long amountMinor, String currency) {}

    /// Growing-context stage: priced buy plus the claimed reservation (the design-out seat claim).
    @SuppressWarnings("JBCT-VO-01")
    record ReservedBuy(PricedBuy priced, UUID reservationId) {
        ValidBuy buy() {
            return priced.buy();
        }

        long amountMinor() {
            return priced.amountMinor();
        }

        String currency() {
            return priced.currency();
        }
    }

    /// Growing-context stage: reserved buy plus the authorized payment receipt.
    @SuppressWarnings("JBCT-VO-01")
    record AuthorizedBuy(ReservedBuy reserved, UUID receiptId) {
        ValidBuy buy() {
            return reserved.buy();
        }

        long amountMinor() {
            return reserved.amountMinor();
        }

        String currency() {
            return reserved.currency();
        }

        UUID reservationId() {
            return reserved.reservationId();
        }
    }

    /// Terminal buy stage: the persisted booking and ticket, ready to notify, publish and respond.
    @SuppressWarnings("JBCT-VO-01")
    record Confirmation(AuthorizedBuy authorized, UUID bookingId, UUID ticketId) {
        Response response() {
            return new Response(bookingId.toString(),
                                ticketId.toString(),
                                authorized.buy().seatStr(),
                                authorized.receiptId().toString(),
                                authorized.amountMinor(),
                                authorized.currency());
        }

        SeatSold fact() {
            return new SeatSold(authorized.buy().seatStr(),
                                authorized.buy().eventStr(),
                                bookingId.toString());
        }

        String customerMailbox() {
            return BuyTicket.customerMailbox(authorized.buy().customerStr());
        }

        String emailBody() {
            return "Your ticket " + ticketId
                 + " for seat " + authorized.buy()
                                            .seatStr()
                 + " is confirmed. Receipt: " + authorized.receiptId();
        }
    }

    /// Closed set of buy failures. Each is a distinct record so route error-mapping can target it by
    /// simple name (see routes.toml).
    sealed interface BuyError extends Cause {
        record SeatUnavailable() implements BuyError {
            @Override
            public String message() {
                return "Seat is no longer available";
            }
        }

        record EventNotSelling() implements BuyError {
            @Override
            public String message() {
                return "Event is not currently selling";
            }
        }

        record CustomerIneligible() implements BuyError {
            @Override
            public String message() {
                return "Customer has too many active bookings";
            }
        }

        record PriceUnavailable() implements BuyError {
            @Override
            public String message() {
                return "No price is available for this event and tier";
            }
        }

        record PaymentDeclined() implements BuyError {
            @Override
            public String message() {
                return "Payment was declined";
            }
        }

        record PaymentProviderUnavailable() implements BuyError {
            @Override
            public String message() {
                return "Payment provider is unavailable";
            }
        }

        record StoreUnavailable() implements BuyError {
            @Override
            public String message() {
                return "Booking store is unavailable";
            }
        }

        static BuyError seatUnavailable() {
            return new SeatUnavailable();
        }

        static BuyError eventNotSelling() {
            return new EventNotSelling();
        }

        static BuyError customerIneligible() {
            return new CustomerIneligible();
        }

        static BuyError priceUnavailable() {
            return new PriceUnavailable();
        }

        static BuyError paymentDeclined() {
            return new PaymentDeclined();
        }

        static BuyError paymentProviderUnavailable() {
            return new PaymentProviderUnavailable();
        }

        static BuyError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    // Best-effort recipient derived from the customer id (the booking domain holds no email address).
    static String customerMailbox(String customerId) {
        return customerId + "@customers.ticketing.example";
    }

    Promise<Response> execute(Request request);

    static BuyTicket buyTicket(@PgSql BookingStore store,
                               @Http HttpClient gateway,
                               @Notify NotificationSender notifier,
                               QuotePrice quotePrice,
                               SaleStatus saleStatus,
                               @SeatSoldPublisher Publisher<SeatSold> seatSold) {
        @SuppressWarnings("JBCT-SEQ-01")
        record buyTicket(BookingStore store,
                         HttpClient gateway,
                         NotificationSender notifier,
                         QuotePrice quotePrice,
                         SaleStatus saleStatus,
                         Publisher<SeatSold> seatSold) implements BuyTicket {
            private static final String FROM_ADDRESS = "tickets@ticketing.example";

            private static final String CONFIRMATION_SUBJECT = "Your ticket is confirmed";
            private static final long MAX_ACTIVE_BOOKINGS = 5;

            // JBCT pattern: Sequencer -- validate -> gate (selling + eligibility) -> price -> reserve
            // -> authorize -> confirm. The BER compensation is declared inside the authorize and
            // confirm steps so each owns its own inverse.
            @Override
            public Promise<Response> execute(Request request) {
                return ValidBuy.validBuy(request)
                               .async()
                               .flatMap(this::ensureSellingAndEligible)
                               .flatMap(this::priceBuy)
                               .flatMap(this::reserve)
                               .flatMap(this::authorize)
                               .flatMap(this::confirm);
            }

            // JBCT pattern: Fork-Join -- the synchronous sale-status read and the eligibility count are
            // independent and run in parallel over the immutable ValidBuy; the join gates the saga.
            private Promise<ValidBuy> ensureSellingAndEligible(ValidBuy valid) {
                return Promise.all(readSaleStatus(valid), countActiveBookings(valid)).flatMap((status, count) -> gate(valid,
                                                                                                                      status,
                                                                                                                      count));
            }

            // Synchronous cross-slice read: any failure or a not-selling event surfaces as EventNotSelling.
            private Promise<SaleStatus.Response> readSaleStatus(ValidBuy valid) {
                return saleStatus.execute(new SaleStatus.Request(valid.eventStr()))
                                 .mapError(_ -> BuyError.eventNotSelling());
            }

            private Promise<Long> countActiveBookings(ValidBuy valid) {
                return store.activeBookingCount(valid.customerUuid())
                            .mapError(_ -> BuyError.storeUnavailable());
            }

            // JBCT pattern: Condition -- route on the sale-status read, no transformation.
            private Promise<ValidBuy> gate(ValidBuy valid, SaleStatus.Response status, long count) {
                return status.onSale()
                       ? eligibilityGate(valid, count)
                       : BuyError.eventNotSelling().promise();
            }

            // JBCT pattern: Condition -- route on eligibility, no transformation.
            private Promise<ValidBuy> eligibilityGate(ValidBuy valid, long count) {
                return count >= MAX_ACTIVE_BOOKINGS
                       ? BuyError.customerIneligible().promise()
                       : Promise.success(valid);
            }

            // JBCT pattern: Leaf -- synchronous authoritative price read from the pricing slice.
            private Promise<PricedBuy> priceBuy(ValidBuy valid) {
                return quotePrice.execute(new QuotePrice.Request(valid.eventStr(),
                                                                 valid.tierStr())).mapError(_ -> BuyError.priceUnavailable())
                                         .map(price -> new PricedBuy(valid,
                                                                     price.amountMinor(),
                                                                     price.currency()));
            }

            // JBCT pattern: Leaf -- design-out seat claim; an empty projection means the seat is taken.
            private Promise<ReservedBuy> reserve(PricedBuy priced) {
                var reservationId = UUID.randomUUID();

                return store.claimSeat(reservationId,
                                       priced.buy().seatUuid(),
                                       priced.buy().eventUuid(),
                                       priced.buy().customerUuid()).mapError(_ -> BuyError.storeUnavailable())
                                      .flatMap(claimed -> claimed.async(BuyError.seatUnavailable()))
                                      .map(_ -> new ReservedBuy(priced, reservationId));
            }

            // JBCT pattern: Aspects -- wrap the authorization in BER compensation; any failure releases
            // the reservation and re-raises the original payment failure.
            private Promise<AuthorizedBuy> authorize(ReservedBuy reserved) {
                return attemptAuthorize(reserved).fold(result -> compensateAuthFailure(reserved, result));
            }

            private Promise<AuthorizedBuy> attemptAuthorize(ReservedBuy reserved) {
                return callGateway(reserved).flatMap(result -> evaluateAuth(reserved, result));
            }

            private Promise<AuthResult> callGateway(ReservedBuy reserved) {
                return gateway.postJson("/authorize",
                                        new AuthRequest(reserved.amountMinor(),
                                                        reserved.currency(),
                                                        reserved.buy().customerStr()),
                                        AuthResult.class)
                              .mapError(_ -> BuyError.paymentProviderUnavailable());
            }

            // JBCT pattern: Condition -- approved continues, declined fails.
            private Promise<AuthorizedBuy> evaluateAuth(ReservedBuy reserved, AuthResult result) {
                return result.approved()
                       ? acceptAuthorization(reserved, result)
                       : BuyError.paymentDeclined().promise();
            }

            private Promise<AuthorizedBuy> acceptAuthorization(ReservedBuy reserved, AuthResult result) {
                return ReceiptId.receiptId(result.receiptId())
                                .mapError(_ -> BuyError.paymentProviderUnavailable())
                                .map(receipt -> new AuthorizedBuy(reserved,
                                                                  receipt.value().value()))
                                .async();
            }

            // BER compensation for the authorize step: release the reservation, then re-raise the cause.
            private Promise<AuthorizedBuy> compensateAuthFailure(ReservedBuy reserved, Result<AuthorizedBuy> result) {
                return result.fold(cause -> releaseThenFail(reserved, cause), Promise::success);
            }

            private Promise<AuthorizedBuy> releaseThenFail(ReservedBuy reserved, Cause cause) {
                return store.releaseReservation(reserved.reservationId())
                            .fold(_ -> cause.promise());
            }

            // JBCT pattern: Sequencer -- persist + issue under BER compensation, then notify + publish.
            private Promise<Response> confirm(AuthorizedBuy authorized) {
                return persistAndIssue(authorized).fold(result -> compensateConfirmFailure(authorized, result))
                                      .flatMap(this::notifyAndPublish);
            }

            private Promise<Confirmation> persistAndIssue(AuthorizedBuy authorized) {
                var bookingId = BookingId.bookingId().value().value();
                var ticketId = TicketId.ticketId().value().value();

                return store.confirmReservation(authorized.reservationId())
                            .mapError(_ -> BuyError.storeUnavailable())
                            .flatMap(confirmed -> confirmed.async(BuyError.seatUnavailable()))
                            .flatMap(_ -> insertRecords(authorized, bookingId, ticketId))
                            .map(_ -> new Confirmation(authorized, bookingId, ticketId));
            }

            // JBCT pattern: Sequencer -- insert ticket, then payment, then the BOOKINGS row LAST. The
            // booking row is the only partial that counts in activeBookingCount and is readable by
            // CancelTicket, so writing it last makes any partial store failure precede it: the confirm-
            // step BER compensation (voidAndRelease) then fully reverses the saga with no orphaned
            // confirmed booking. (tickets/payments carry no FK to bookings, so the reorder is legal;
            // a stranded ticket/payment row is unreachable through every booking-keyed read.)
            private Promise<Unit> insertRecords(AuthorizedBuy authorized, UUID bookingId, UUID ticketId) {
                var paymentId = UUID.randomUUID();

                return store.insertTicket(ticketId,
                                          bookingId,
                                          authorized.buy().seatUuid()).flatMap(_ -> store.insertPayment(paymentId,
                                                                                                        bookingId,
                                                                                                        "authorized",
                                                                                                        authorized.receiptId(),
                                                                                                        authorized.amountMinor(),
                                                                                                        authorized.currency()))
                                         .flatMap(_ -> store.insertBooking(bookingId,
                                                                           authorized.reservationId(),
                                                                           authorized.buy().seatUuid(),
                                                                           authorized.buy().eventUuid(),
                                                                           authorized.buy().customerUuid(),
                                                                           ticketId))
                                         .mapError(_ -> BuyError.storeUnavailable());
            }

            // BER compensation for the confirm step: void the authorization and release the reservation,
            // then re-raise the original cause.
            private Promise<Confirmation> compensateConfirmFailure(AuthorizedBuy authorized,
                                                                   Result<Confirmation> result) {
                return result.fold(cause -> voidAndRelease(authorized, cause), Promise::success);
            }

            private Promise<Confirmation> voidAndRelease(AuthorizedBuy authorized, Cause cause) {
                return voidAuthorization(authorized).flatMap(_ -> store.releaseReservation(authorized.reservationId()))
                                        .fold(_ -> cause.promise());
            }

            // Best-effort gateway void (recovered to Unit); the saga re-raises the original cause anyway.
            private Promise<Unit> voidAuthorization(AuthorizedBuy authorized) {
                return gateway.postJson("/void",
                                        new VoidRequest(authorized.receiptId().toString()),
                                        VoidResult.class).mapToUnit()
                                       .recover(_ -> Unit.unit());
            }

            // JBCT pattern: Sequencer -- best-effort notify (FER), then publish SeatSold and respond.
            private Promise<Response> notifyAndPublish(Confirmation confirmation) {
                return sendConfirmation(confirmation).flatMap(_ -> publishSold(confirmation));
            }

            // FER: a notification failure is swallowed so it never fails the buy.
            private Promise<Unit> sendConfirmation(Confirmation confirmation) {
                return notifier.send(confirmationEmail(confirmation))
                               .mapToUnit()
                               .recover(_ -> Unit.unit());
            }

            private Notification confirmationEmail(Confirmation confirmation) {
                return Notification.Email.email(FROM_ADDRESS,
                                                List.of(confirmation.customerMailbox()),
                                                CONFIRMATION_SUBJECT,
                                                NotificationBody.Text.text(confirmation.emailBody()));
            }

            private Promise<Response> publishSold(Confirmation confirmation) {
                return seatSold.publish(confirmation.fact())
                               .map(_ -> confirmation.response());
            }
        }

        return new buyTicket(store, gateway, notifier, quotePrice, saleStatus, seatSold);
    }
}
