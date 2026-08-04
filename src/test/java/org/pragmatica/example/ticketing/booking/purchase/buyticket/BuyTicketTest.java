package org.pragmatica.example.ticketing.booking.purchase.buyticket;

import java.util.UUID;

import org.pragmatica.aether.resource.http.HttpClient;
import org.pragmatica.aether.resource.notification.NotificationResult;
import org.pragmatica.aether.resource.notification.NotificationSender;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.booking.BookingStore;
import org.pragmatica.example.ticketing.booking.FailingBookingStore;
import org.pragmatica.example.ticketing.booking.FakeGateway;
import org.pragmatica.example.ticketing.booking.InMemoryBookingStore;
import org.pragmatica.example.ticketing.eventmanagement.capacity.seatsellability.SeatSellability;
import org.pragmatica.example.ticketing.eventmanagement.sales.salestatus.SaleStatus;
import org.pragmatica.example.ticketing.pricing.quoting.quoteprice.QuotePrice;
import org.pragmatica.example.ticketing.shared.event.SeatSold;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Integration-first tests for the buy-ticket saga: the assembled slice runs with all business
/// logic; only the adapters (store, gateway, notifier, publisher) and the synchronous cross-slice
/// dependencies (pricing quote, sale status) are faked. The fakes mirror the semantics that matter
/// -- the design-out seat claim (one reservation row per seat, reclaimable by the same customer's
/// own live hold but never over a confirmed sale or another customer's hold), BER compensation
/// releasing the reservation and voiding the authorization on every post-authorization failure, and
/// the seat becoming resellable once its sale is cancelled.
class BuyTicketTest {
    private final NotificationSender notifier = _ -> Promise.success(NotificationResult.notificationResult("msg", "test"));

    private final QuotePrice quotePrice = request -> Promise.success(new QuotePrice.Response(request.event(),
                                                                                             request.tier(),
                                                                                             2500,
                                                                                             "USD",
                                                                                             1));

    private final QuotePrice failingQuote = _ -> Causes.cause("no price").promise();
    private final Publisher<SeatSold> seatSold = _ -> Promise.UNIT;
    private final Publisher<SeatSold> failingSeatSold = _ -> Causes.cause("event bus down").promise();

    private BuyTicket buildSlice(BookingStore store,
                                 HttpClient gateway,
                                 QuotePrice quote,
                                 boolean onSale,
                                 Publisher<SeatSold> publisher) {
        return buildSlice(store, gateway, quote, onSale, publisher, SELLABLE);
    }

    // Seat-state gate stubs. The `seats` table is owned by event-management, so the purchase path
    // reaches it through this slice; a seat the operator blocked must be refused before the claim.
    private static final SeatSellability SELLABLE = request -> Promise.success(new SeatSellability.Response(request.seat(),
                                                                                                            "available",
                                                                                                            true));

    private static final SeatSellability BLOCKED = request -> Promise.success(new SeatSellability.Response(request.seat(),
                                                                                                           "blocked",
                                                                                                           false));

    private BuyTicket buildSlice(BookingStore store,
                                 HttpClient gateway,
                                 QuotePrice quote,
                                 boolean onSale,
                                 Publisher<SeatSold> publisher,
                                 SeatSellability sellability) {
        SaleStatus saleStatus = request -> Promise.success(new SaleStatus.Response(request.event(), onSale, ""));

        return BuyTicket.buyTicket(store, gateway, notifier, quote, saleStatus, sellability, publisher);
    }

    @Test
    void execute_blockedSeat_isRefused() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, new FakeGateway(true), quotePrice, true, seatSold, BLOCKED);

        slice.execute(new BuyTicket.Request(UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            "STANDARD"))
             .await()
             .onSuccess(_ -> fail("a blocked seat must not be sellable"))
             .onFailure(cause -> assertThat(cause).isEqualTo(BuyTicket.BuyError.StateConflict.SEAT_NOT_SELLABLE));
    }

    private BuyTicket buildSlice(BookingStore store, HttpClient gateway, QuotePrice quote, boolean onSale) {
        return buildSlice(store, gateway, quote, onSale, seatSold);
    }

    private BuyTicket buildSlice(InMemoryBookingStore store, boolean approved, boolean onSale) {
        return buildSlice(store, new FakeGateway(approved), quotePrice, onSale);
    }

    private BuyTicket.Request request(UUID customer, UUID event, UUID seat) {
        return new BuyTicket.Request(customer.toString(), event.toString(), seat.toString(), "STANDARD");
    }

    @Test
    void execute_validRequest_returnsBookingDetails() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, true, true);
        var event = UUID.randomUUID().toString();
        var seat = UUID.randomUUID().toString();

        slice.execute(new BuyTicket.Request(UUID.randomUUID().toString(),
                                            event,
                                            seat,
                                            "STANDARD"))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> {
                            assertThat(response.seat()).isEqualTo(seat);
                            assertThat(response.amountMinor()).isEqualTo(2500);
                            assertThat(response.currency()).isEqualTo("USD");
                            assertThat(response.booking()).isNotBlank();
                            assertThat(response.ticket()).isNotBlank();
                            assertThat(response.receipt()).isNotBlank();
                        });
    }

    @Test
    void execute_seatAlreadyHeld_returnsSeatUnavailable() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, true, true);
        var event = UUID.randomUUID();
        var seat = UUID.randomUUID();

        store.seedHold(seat, event, UUID.randomUUID());
        slice.execute(request(UUID.randomUUID(),
                              event,
                              seat))
             .await()
             .onSuccess(response -> fail("Expected SeatUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("no longer available"));
    }

    // C2: a customer's OWN live hold must convert into a purchase -- the claim guard admits the same
    // customer's held reservation, so acquire-hold is no longer a dead end.
    @Test
    void execute_ownLiveHold_confirmsPurchase() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, true, true);
        var event = UUID.randomUUID();
        var seat = UUID.randomUUID();
        var customer = UUID.randomUUID();

        store.seedHold(seat, event, customer);
        slice.execute(request(customer, event, seat))
             .await()
             .onFailure(cause -> fail("Own live hold must convert to a purchase: " + cause.message()))
             .onSuccess(response -> assertThat(response.seat()).isEqualTo(seat.toString()));
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("confirmed");
    }

    // The same guard must stay closed for everybody else: a confirmed sale is never reclaimable, not
    // even by the customer who owns it.
    @Test
    void execute_confirmedReservation_returnsSeatUnavailable() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, true, true);
        var event = UUID.randomUUID();
        var seat = UUID.randomUUID();
        var customer = UUID.randomUUID();

        store.seedConfirmedReservation(seat, event, customer);
        slice.execute(request(customer, event, seat))
             .await()
             .onSuccess(response -> fail("Expected SeatUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("no longer available"));
    }

    // C1: once a sale is cancelled the seat must be sellable again. The reclaim rotates a non-key
    // claim identity, so the booking row that recorded the previous claim cannot block it.
    @Test
    void execute_seatCancelledAfterPurchase_allowsResale() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, true, true);
        var event = UUID.randomUUID();
        var seat = UUID.randomUUID();

        slice.execute(request(UUID.randomUUID(),
                              event,
                              seat))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> cancelPurchase(store, response, seat));
        slice.execute(request(UUID.randomUUID(),
                              event,
                              seat))
             .await()
             .onFailure(cause -> fail("Cancelled seat must be resellable: " + cause.message()))
             .onSuccess(response -> assertThat(response.seat()).isEqualTo(seat.toString()));
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("confirmed");
    }

    @Test
    void execute_eventNotSelling_returnsEventNotSelling() {
        var slice = buildSlice(new InMemoryBookingStore(), true, false);

        slice.execute(new BuyTicket.Request(UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            "STANDARD"))
             .await()
             .onSuccess(response -> fail("Expected EventNotSelling"))
             .onFailure(cause -> assertThat(cause.message()).contains("not currently selling"));
    }

    @Test
    void execute_paymentDeclined_releasesReservationAndFails() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, false, true);
        var event = UUID.randomUUID().toString();
        var seat = UUID.randomUUID().toString();

        slice.execute(new BuyTicket.Request(UUID.randomUUID().toString(),
                                            event,
                                            seat,
                                            "STANDARD"))
             .await()
             .onSuccess(response -> fail("Expected PaymentDeclined"))
             .onFailure(cause -> assertThat(cause.message()).contains("declined"));
        assertThat(store.reservationStateBySeat(UUID.fromString(seat))).isEqualTo("cancelled");
    }

    // The highest-risk BER path: the gateway authorizes, then the confirm-step store write fails. The
    // saga must VOID the authorization at the gateway and RELEASE the reservation, leaving no orphaned
    // confirmed booking (activeBookingCount stays 0).
    @Test
    void execute_storeFailsAfterAuthorization_voidsAuthorizationAndReleasesReservation() {
        var gateway = new FakeGateway(true);
        var store = new FailingBookingStore(FailingBookingStore.FailOp.INSERT_BOOKING);
        var slice = buildSlice(store, gateway, quotePrice, true);
        var event = UUID.randomUUID();
        var seat = UUID.randomUUID();
        var customer = UUID.randomUUID();

        slice.execute(request(customer, event, seat))
             .await()
             .onSuccess(response -> fail("Expected store failure"))
             .onFailure(cause -> assertThat(cause.message()).contains("unavailable"));
        assertThat(gateway.calls()).contains("/authorize", "/void");
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("cancelled");
        store.activeBookingCount(customer)
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(count -> assertThat(count.longValue()).isZero());
    }

    // H1: the gateway APPROVED but returned a receipt the slice cannot parse. The money is captured,
    // so the authorization must be voided -- releasing the reservation alone leaves the customer paid
    // and ticketless.
    @Test
    void execute_approvedWithUnparseableReceipt_voidsAuthorizationAndReleasesReservation() {
        var gateway = FakeGateway.approvingWith("not-a-uuid");
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, gateway, quotePrice, true);
        var event = UUID.randomUUID();
        var seat = UUID.randomUUID();

        slice.execute(request(UUID.randomUUID(),
                              event,
                              seat))
             .await()
             .onSuccess(response -> fail("Expected PaymentProviderUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("provider is unavailable"));
        assertThat(gateway.calls()).contains("/authorize", "/void");
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("cancelled");
    }

    // H2: the purchase is committed before the SeatSold fact is published, so a publish failure must
    // not fail the buy -- otherwise the buyer is charged, holds a ticket, and is told the buy failed.
    @Test
    void execute_factPublishFails_stillReturnsCommittedBooking() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, new FakeGateway(true), quotePrice, true, failingSeatSold);
        var event = UUID.randomUUID();
        var seat = UUID.randomUUID();
        var customer = UUID.randomUUID();

        slice.execute(request(customer, event, seat))
             .await()
             .onFailure(cause -> fail("A committed purchase must not fail on fact publish: " + cause.message()))
             .onSuccess(response -> {
                            assertThat(response.booking()).isNotBlank();
                            assertThat(response.ticket()).isNotBlank();
                        });
        store.activeBookingCount(customer)
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(count -> assertThat(count.longValue()).isEqualTo(1));
    }

    @Test
    void execute_customerAtBookingLimit_returnsCustomerIneligible() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, true, true);
        var customer = UUID.randomUUID();

        seedConfirmedBookings(store, customer, 5);
        slice.execute(new BuyTicket.Request(customer.toString(),
                                            UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            "STANDARD"))
             .await()
             .onSuccess(response -> fail("Expected CustomerIneligible"))
             .onFailure(cause -> assertThat(cause.message()).contains("active bookings"));
    }

    @Test
    void execute_priceLookupFails_returnsPriceUnavailable() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, new FakeGateway(true), failingQuote, true);

        slice.execute(new BuyTicket.Request(UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            "STANDARD"))
             .await()
             .onSuccess(response -> fail("Expected PriceUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("price is available"));
    }

    @Test
    void execute_gatewayUnavailable_returnsPaymentProviderUnavailable() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, FakeGateway.failing("/authorize"), quotePrice, true);
        var seat = UUID.randomUUID();

        slice.execute(new BuyTicket.Request(UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            seat.toString(),
                                            "STANDARD"))
             .await()
             .onSuccess(response -> fail("Expected PaymentProviderUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("provider is unavailable"));
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("cancelled");
    }

    @Test
    void execute_storeCountFails_returnsStoreUnavailable() {
        var store = new FailingBookingStore(FailingBookingStore.FailOp.ACTIVE_BOOKING_COUNT);
        var slice = buildSlice(store, new FakeGateway(true), quotePrice, true);

        slice.execute(new BuyTicket.Request(UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            "STANDARD"))
             .await()
             .onSuccess(response -> fail("Expected StoreUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("store is unavailable"));
    }

    @Test
    void validBuy_malformedCustomer_returnsFailure() {
        BuyTicket.ValidBuy.validBuy(new BuyTicket.Request("not-a-uuid",
                                                          UUID.randomUUID().toString(),
                                                          UUID.randomUUID().toString(),
                                                          "STANDARD")).onSuccess(valid -> fail("Expected validation failure"));
    }

    private void cancelPurchase(InMemoryBookingStore store, BuyTicket.Response response, UUID seat) {
        store.cancelBooking(UUID.fromString(response.booking())).await().onFailure(cause -> fail(cause.message()));
        store.cancelReservationBySeat(seat).await().onFailure(cause -> fail(cause.message()));
    }

    private void seedConfirmedBookings(InMemoryBookingStore store, UUID customer, int count) {
        for (int i = 0; i < count; i++) {
            store.insertBooking(UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                customer,
                                UUID.randomUUID())
                 .await()
                 .onFailure(cause -> fail(cause.message()));
        }
    }

    /// The 500-instead-of-400 contract. Slices validate through the SHARED value objects, whose
    /// failures the generated router cannot see -- it builds its error switch from this package's
    /// Cause types alone, so a shared cause (or the composite `Result.all` wraps them in) falls
    /// through to HTTP 500. Verified by mutation: deleting the closing `Validation::firstFailure`
    /// turns BOTH tests below red, because `Result.all` builds a composite even around a single
    /// failure -- per-field `mapError` alone is not sufficient.
    @Test
    void validBuy_malformedSeat_returnsSliceLocalInvalidRequest() {
        BuyTicket.ValidBuy.validBuy(new BuyTicket.Request("11111111-1111-1111-1111-111111111111",
                                                          "11111111-1111-1111-1111-111111111111",
                                                          "not-a-uuid",
                                                          "STANDARD"))
                          .onSuccess(_ -> fail("Expected validation to fail"))
                          .onFailure(cause -> {
                                         assertThat(cause).isInstanceOf(BuyTicket.BuyError.InvalidRequest.class);
                                         assertThat(cause.message()).contains("seat");
                                     });
    }

    @Test
    void validBuy_multipleInvalidFields_returnsSliceLocalCauseNotComposite() {
        BuyTicket.ValidBuy.validBuy(new BuyTicket.Request("not-a-uuid", "not-a-uuid", "not-a-uuid", "STANDARD"))
                          .onSuccess(_ -> fail("Expected validation to fail"))
                          .onFailure(cause -> assertThat(cause).isInstanceOf(BuyTicket.BuyError.InvalidRequest.class));
    }
}
