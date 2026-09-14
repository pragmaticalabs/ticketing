package org.pragmatica.example.ticketing.booking.purchase.buyticket;

import java.util.List;
import java.util.UUID;

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
import org.pragmatica.example.ticketing.eventmanagement.capacity.seatsellability.SeatSellability;
import org.pragmatica.example.ticketing.eventmanagement.sales.salestatus.SaleStatus;
import org.pragmatica.example.ticketing.pricing.quoting.quoteprice.QuotePrice;
import org.pragmatica.example.ticketing.shared.BookingId;
import org.pragmatica.example.ticketing.shared.CustomerId;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.PriceTier;
import org.pragmatica.example.ticketing.shared.ReceiptId;
import org.pragmatica.example.ticketing.shared.SeatId;
import org.pragmatica.example.ticketing.shared.TicketId;
import org.pragmatica.example.ticketing.shared.Validation;
import org.pragmatica.example.ticketing.shared.event.SeatSold;
import org.pragmatica.example.ticketing.shared.event.SeatSoldPublisher;


/// Use case: buy a ticket for a seat (the BER-saga centerpiece). Telescope leaf -- system
/// `ticketing` -> subsystem `booking` -> workflow `purchase` -> use case `buy-ticket`. One use
/// case, one `Request`/`Response` pair, one `execute` method.
///
/// Recovery, per step and by mechanism:
///   - **design-out (seat contention)**: the seat claim is a single guarded `INSERT ... ON CONFLICT
///     (seat_id) DO UPDATE ... RETURNING claim_id`; the loser of a contended seat fast-fails with
///     SeatUnavailable -- no lock, no race. The saga carries the claim identity the database
///     returned, so a concurrent reclaim rotates it and every later guarded transition fails closed.
///     A customer's own live hold is admitted by the guard, which is how a hold becomes a purchase.
///   - **BER (before the purchase is committed)**: every failure after the seat is claimed releases
///     the reservation, and every failure after the gateway has approved also voids the
///     authorization -- including an approval whose receipt cannot be parsed, which is voided with
///     the raw receipt the gateway returned. Post-state of any failed buy: no reservation, no
///     captured money. Compensation lives in dedicated private helpers that re-raise the original
///     typed failure; the void is best-effort, so a gateway that is down during compensation leaves
///     an authorization for the provider's own expiry to reap.
///   - **FER (after the purchase is committed)**: the confirmation notification and the `SeatSold`
///     fact publish are both best-effort single attempts. Once the booking row is written the
///     purchase is irreversible, so neither may fail the call. A lost `SeatSold` leaves the read
///     projections stale for that seat (see `publishSold`).
///
/// Not covered by any of the above: a gateway timeout on an authorization that in fact succeeded.
/// The slice never learns the receipt id, and `/void` is keyed by receipt, so nothing can be voided
/// -- the reservation is released and the stray authorization is left to the provider's expiry.
///
/// Sale status and the authoritative price are read **synchronously** from the event-management and
/// pricing slices (injected as plain factory parameters); the payment gateway is an `@Http` resource
/// and notifications an `@Notify` resource.
@Slice
public interface BuyTicket {
    record Request(String customer, String event, String seat, String tier) {}

    record Response(String booking, String ticket, String seat, String receipt, long amountMinor, String currency) {}

    // Payment-gateway wire DTOs (plain records; the @Http client serializes/deserializes them as JSON).
    record AuthRequest(long amountMinor, String currency, String customer) {}

    record AuthResult(boolean approved, String receiptId) {}

    record VoidRequest(String receiptId) {}

    record VoidResult(String status) {}

    /// Validated buy target. Raw request fields are parsed into value objects; all failures surface
    /// together via Result.all.
    ///
    /// Each field is mapped to a cause declared in this slice's own package, and the closing `mapError`
    /// unwraps the composite Result.all builds around them: the generated router's error switch is
    /// built from this package's `Cause` types alone, so a shared `SeatId.Error` -- or the composite
    /// wrapping it -- would fall through to HTTP 500 rather than reaching the client as a refusal it
    /// can act on.
    record ValidBuy(CustomerId customer, EventId event, SeatId seat, PriceTier tier) {
        static Result<ValidBuy> validBuy(Request request) {
            return Result.all(CustomerId.customerId(request.customer()).mapError(BuyError::invalidCustomer),
                              EventId.eventId(request.event()).mapError(BuyError::invalidEvent),
                              SeatId.seatId(request.seat()).mapError(BuyError::invalidSeat),
                              PriceTier.priceTier(request.tier()).mapError(BuyError::unacceptableTier))
                         .map(ValidBuy::new)
                         .mapError(Validation::firstFailure);
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
    record PricedBuy(ValidBuy buy, long amountMinor, String currency) {}

    /// Growing-context stage: priced buy plus the claimed seat (the design-out seat claim). Carries
    /// the claim identity the database returned, never one generated optimistically here.
    record ReservedBuy(PricedBuy priced, UUID claimId) {
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

        UUID claimId() {
            return reserved.claimId();
        }
    }

    /// Terminal buy stage: the persisted booking and ticket, ready to notify, publish and respond.
    /// `version` is the reservation slot's sequence at the confirming transition; it stamps the
    /// `SeatSold` fact so consumers can order it against other facts for the same seat.
    record Confirmation(AuthorizedBuy authorized, UUID bookingId, UUID ticketId, long version) {
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
                                bookingId.toString(),
                                version);
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

    Promise<Response> execute(Request request);

    /// Closed set of buy failures. Fixed-message refusals are grouped into one enum per HTTP
    /// status so route error-mapping can target a whole status class by that enum's simple name (see
    /// routes.toml); data-carrying refusals stay records.
    sealed interface BuyError extends Cause {
        /// Fixed-message refusals by a guard on current state. Every constant here is an HTTP 409 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 409 must not be added to it, and one
        /// `*StateConflict*` pattern maps the whole enum.
        enum StateConflict implements BuyError {
            SEAT_UNAVAILABLE("Seat is no longer available"),
            EVENT_NOT_SELLING("Event is not currently selling"),
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

        /// Fixed-message payment refusals by the gateway. Every constant here is an HTTP 402 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 402 must not be added to it, and one
        /// `*PaymentRefused*` pattern maps the whole enum.
        enum PaymentRefused implements BuyError {
            DECLINED("Payment was declined");
            private final String message;
            PaymentRefused(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        /// Fixed-message well-formed requests refused on meaning. Every constant here is an HTTP 422 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 422 must not be added to it, and one
        /// `*Unprocessable*` pattern maps the whole enum.
        enum Unprocessable implements BuyError {
            CUSTOMER_INELIGIBLE("Customer has too many active bookings");
            private final String message;
            Unprocessable(String message) {
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
        enum ServiceUnavailable implements BuyError {
            BOOKING_STORE("Booking store is unavailable"),
            PAYMENT_PROVIDER("Payment provider is unavailable"),
            PRICE("No price is available for this event and tier");
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
        record InvalidRequest(String field, String detail) implements BuyError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        /// Client-facing validation refusal (HTTP 422): a request field parsed cleanly but its value
        /// lies outside the field's admissible domain -- here, a well-formed token that names no member
        /// of the closed `PriceTier` set. Well-formed-but-unacceptable is a semantic refusal rather than
        /// a syntax error, which is why it earns 422 where [InvalidRequest] earns 400. It sits beside
        /// [Unprocessable#CUSTOMER_INELIGIBLE], the failure this slice already reports as 422.
        record UnacceptableValue(String field, String detail) implements BuyError {
            @Override
            public String message() {
                return "Unacceptable value for request field '" + field + "': " + detail;
            }
        }

        static BuyError seatUnavailable() {
            return StateConflict.SEAT_UNAVAILABLE;
        }

        static BuyError eventNotSelling() {
            return StateConflict.EVENT_NOT_SELLING;
        }

        static BuyError customerIneligible() {
            return Unprocessable.CUSTOMER_INELIGIBLE;
        }

        static BuyError priceUnavailable() {
            return ServiceUnavailable.PRICE;
        }

        static BuyError paymentDeclined() {
            return PaymentRefused.DECLINED;
        }

        static BuyError paymentProviderUnavailable() {
            return ServiceUnavailable.PAYMENT_PROVIDER;
        }

        static BuyError storeUnavailable() {
            return ServiceUnavailable.BOOKING_STORE;
        }

        static BuyError seatNotSellable() {
            return StateConflict.SEAT_NOT_SELLABLE;
        }

        static BuyError invalidCustomer(Cause cause) {
            return new InvalidRequest("customer", cause.message());
        }

        static BuyError invalidEvent(Cause cause) {
            return new InvalidRequest("event", cause.message());
        }

        static BuyError invalidSeat(Cause cause) {
            return new InvalidRequest("seat", cause.message());
        }

        static BuyError unacceptableTier(Cause cause) {
            return new UnacceptableValue("tier", cause.message());
        }
    }

    // Best-effort recipient derived from the customer id (the booking domain holds no email address).
    static String customerMailbox(String customerId) {
        return customerId + "@customers.ticketing.example";
    }

    static BuyTicket buyTicket(@PgSql BookingStore store,
                               @Http HttpClient gateway,
                               @Notify NotificationSender notifier,
                               QuotePrice quotePrice,
                               SaleStatus saleStatus,
                               SeatSellability seatSellability,
                               @SeatSoldPublisher Publisher<SeatSold> seatSold) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
        record buyTicket(BookingStore store,
                         HttpClient gateway,
                         NotificationSender notifier,
                         QuotePrice quotePrice,
                         SaleStatus saleStatus,
                         SeatSellability seatSellability,
                         Publisher<SeatSold> seatSold) implements BuyTicket {
            private static final String FROM_ADDRESS = "tickets@ticketing.example";

            private static final String CONFIRMATION_SUBJECT = "Your ticket is confirmed";
            private static final long MAX_ACTIVE_BOOKINGS = 5;

            // JBCT pattern: Sequencer -- validate -> gate (selling + eligibility) -> price -> reserve
            // -> authorize -> confirm. The BER compensation is declared inside the authorize and
            // confirm steps so each owns its own inverse.
            @Override
            public Promise<Response> execute(Request request) {
                // Split where the saga stops reading and starts mutating: everything above is gating and
                // pricing, everything below claims the seat and can require compensation.
                var priced = ValidBuy.validBuy(request)
                                     .async()
                                     .flatMap(this::ensureSellingAndEligible)
                                     .flatMap(this::priceBuy);

                return priced.flatMap(this::reserve)
                             .flatMap(this::authorize)
                             .flatMap(this::confirm);
            }

            // JBCT pattern: Fork-Join -- the two synchronous cross-slice reads (sale status, seat
            // sellability) and the eligibility count are independent and run in parallel over the
            // immutable ValidBuy; the join gates the saga. Sellability rides the existing fork rather
            // than adding a serial round-trip to the hot purchase path.
            private Promise<ValidBuy> ensureSellingAndEligible(ValidBuy valid) {
                return Promise.all(readSaleStatus(valid), countActiveBookings(valid), readSeatSellability(valid)).flatMap((status, count, sellability) -> gate(valid,
                                                                                                                                                               status,
                                                                                                                                                               count,
                                                                                                                                                               sellability));
            }

            // Synchronous cross-slice read: any failure or a not-selling event surfaces as EventNotSelling.
            private Promise<SaleStatus.Response> readSaleStatus(ValidBuy valid) {
                return saleStatus.execute(new SaleStatus.Request(valid.eventStr()))
                                 .mapError(_ -> BuyError.eventNotSelling());
            }

            // Synchronous cross-slice read: the `seats` table is owned by event-management, so a seat
            // the operator has blocked or withdrawn is only visible through its slice.
            private Promise<SeatSellability.Response> readSeatSellability(ValidBuy valid) {
                return seatSellability.execute(new SeatSellability.Request(valid.seatStr()))
                                      .mapError(_ -> BuyError.seatNotSellable());
            }

            private Promise<Long> countActiveBookings(ValidBuy valid) {
                return store.activeBookingCount(valid.customerUuid())
                            .mapError(_ -> BuyError.storeUnavailable());
            }

            // JBCT pattern: Condition -- route on the sale-status read, no transformation.
            private Promise<ValidBuy> gate(ValidBuy valid,
                                           SaleStatus.Response status,
                                           long count,
                                           SeatSellability.Response sellability) {
                return status.onSale()
                       ? sellabilityGate(valid, count, sellability)
                       : BuyError.eventNotSelling().promise();
            }

            // JBCT pattern: Condition -- route on seat sellability, no transformation.
            private Promise<ValidBuy> sellabilityGate(ValidBuy valid,
                                                      long count,
                                                      SeatSellability.Response sellability) {
                return sellability.sellable()
                       ? eligibilityGate(valid, count)
                       : BuyError.seatNotSellable().promise();
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
                                                                 valid.tierStr()))
                                 .mapError(_ -> BuyError.priceUnavailable())
                                 .map(price -> new PricedBuy(valid,
                                                             price.amountMinor(),
                                                             price.currency()));
            }

            // JBCT pattern: Leaf -- design-out seat claim; an empty projection means the seat is taken.
            // The claim identity comes back from the database, so the saga can only ever act on the
            // claim it actually won.
            private Promise<ReservedBuy> reserve(PricedBuy priced) {
                return store.claimSeat(priced.buy().seatUuid(),
                                       priced.buy().eventUuid(),
                                       priced.buy().customerUuid())
                            .mapError(_ -> BuyError.storeUnavailable())
                            .flatMap(claimed -> claimed.async(BuyError.seatUnavailable()))
                            .map(claim -> new ReservedBuy(priced,
                                                          claim.claimId()));
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

            // The gateway approved, so money is captured from here on. If the receipt cannot be
            // parsed we hold no usable handle for the payment, but we do hold the raw one the gateway
            // sent -- void with that before failing, or the customer is charged for nothing.
            private Promise<AuthorizedBuy> acceptAuthorization(ReservedBuy reserved, AuthResult result) {
                return ReceiptId.receiptId(result.receiptId())
                                .mapError(_ -> BuyError.paymentProviderUnavailable())
                                .map(receipt -> new AuthorizedBuy(reserved,
                                                                  receipt.value().value()))
                                .async()
                                .fold(parsed -> voidUnusableAuthorization(result, parsed));
            }

            private Promise<AuthorizedBuy> voidUnusableAuthorization(AuthResult result, Result<AuthorizedBuy> parsed) {
                return parsed.fold(cause -> voidRawThenFail(result.receiptId(), cause), Promise::success);
            }

            private Promise<AuthorizedBuy> voidRawThenFail(String receiptId, Cause cause) {
                return voidReceipt(receiptId).flatMap(_ -> cause.promise());
            }

            // BER compensation for the authorize step: release the reservation, then re-raise the cause.
            // Any authorization that got as far as producing a receipt is voided by the step that
            // produced it, so this compensation owns only the reservation it created.
            private Promise<AuthorizedBuy> compensateAuthFailure(ReservedBuy reserved, Result<AuthorizedBuy> result) {
                return result.fold(cause -> releaseThenFail(reserved, cause), Promise::success);
            }

            private Promise<AuthorizedBuy> releaseThenFail(ReservedBuy reserved, Cause cause) {
                return store.releaseReservation(reserved.claimId())
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

                return store.confirmReservation(authorized.claimId())
                            .mapError(_ -> BuyError.storeUnavailable())
                            .flatMap(confirmed -> confirmed.async(BuyError.seatUnavailable()))
                            .flatMap(claim -> issueUnder(authorized,
                                                         bookingId,
                                                         ticketId,
                                                         claim.version()));
            }

            /// The confirming transition's version is the seat's position in its own sequence, so the
            /// `SeatSold` fact published downstream can be ordered against every other fact for that seat.
            private Promise<Confirmation> issueUnder(AuthorizedBuy authorized,
                                                     UUID bookingId,
                                                     UUID ticketId,
                                                     long version) {
                return insertRecords(authorized, bookingId, ticketId).map(_ -> new Confirmation(authorized,
                                                                                                bookingId,
                                                                                                ticketId,
                                                                                                version));
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
                                          authorized.buy().seatUuid())
                            .flatMap(_ -> store.insertPayment(paymentId,
                                                              bookingId,
                                                              "authorized",
                                                              authorized.receiptId(),
                                                              authorized.amountMinor(),
                                                              authorized.currency()))
                            .flatMap(_ -> store.insertBooking(bookingId,
                                                              authorized.claimId(),
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
                return voidAuthorization(authorized).flatMap(_ -> store.releaseReservation(authorized.claimId()))
                                        .fold(_ -> cause.promise());
            }

            // Best-effort gateway void (recovered to Unit); the saga re-raises the original cause anyway.
            private Promise<Unit> voidAuthorization(AuthorizedBuy authorized) {
                return voidReceipt(authorized.receiptId().toString());
            }

            private Promise<Unit> voidReceipt(String receiptId) {
                return gateway.postJson("/void",
                                        new VoidRequest(receiptId),
                                        VoidResult.class)
                              .mapToUnit()
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

            // FER: the buy is already committed and irreversible by the time the fact is published, so
            // a publish failure is swallowed rather than reported to a buyer who has been charged and
            // holds a valid ticket. Guarantee earned: the response is truthful about the purchase, not
            // about the fact. Mechanism: a single attempt -- there is no retry and no outbox, so a lost
            // SeatSold leaves the availability and pricing projections stale for that seat until the
            // next fact about it, or an operator re-drive.
            private Promise<Response> publishSold(Confirmation confirmation) {
                return seatSold.publish(confirmation.fact())
                               .recover(_ -> Unit.unit())
                               .map(_ -> confirmation.response());
            }
        }

        return new buyTicket(store, gateway, notifier, quotePrice, saleStatus, seatSellability, seatSold);
    }
}
