package org.pragmatica.example.ticketing.booking.purchase.buyticket;

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
import org.pragmatica.example.ticketing.eventmanagement.sales.salestatus.SaleStatus;
import org.pragmatica.example.ticketing.pricing.quoting.quoteprice.QuotePrice;
import org.pragmatica.example.ticketing.shared.event.SeatSold;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Integration-first tests for the buy-ticket saga: the assembled slice runs with all business
/// logic; only the adapters (store, gateway, notifier, publisher) and the synchronous cross-slice
/// dependencies (pricing quote, sale status) are faked. The fakes mirror the semantics that matter
/// -- the design-out seat claim (one active reservation per seat) and BER compensation releasing the
/// reservation on a payment failure, and voiding the authorization on a confirm-step store failure.
class BuyTicketTest {
    private final NotificationSender notifier = _ -> Promise.success(NotificationResult.notificationResult("msg", "test"));

    private final QuotePrice quotePrice = request -> Promise.success(new QuotePrice.Response(request.event(),
                                                                                             request.tier(),
                                                                                             2500,
                                                                                             "USD",
                                                                                             1));

    private final QuotePrice failingQuote = _ -> Causes.cause("no price").promise();

    private final Publisher<SeatSold> seatSold = _ -> Promise.UNIT;

    private BuyTicket buildSlice(BookingStore store, HttpClient gateway, QuotePrice quote, boolean onSale) {
        SaleStatus saleStatus = request -> Promise.success(new SaleStatus.Response(request.event(), onSale, ""));

        return BuyTicket.buyTicket(store, gateway, notifier, quote, saleStatus, seatSold);
    }

    private BuyTicket buildSlice(InMemoryBookingStore store, boolean approved, boolean onSale) {
        return buildSlice(store, new FakeGateway(approved), quotePrice, onSale);
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
                                            "STANDARD")).await().onFailure(cause -> fail(cause.message())).onSuccess(response -> {
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
        var event = UUID.randomUUID().toString();
        var seat = UUID.randomUUID().toString();

        store.claimSeat(UUID.randomUUID(), UUID.fromString(seat), UUID.fromString(event), UUID.randomUUID()).await().onFailure(cause -> fail(cause.message()));
        slice.execute(new BuyTicket.Request(UUID.randomUUID().toString(),
                                            event,
                                            seat,
                                            "STANDARD")).await().onSuccess(response -> fail("Expected SeatUnavailable")).onFailure(cause -> assertThat(cause.message()).contains("no longer available"));
    }

    @Test
    void execute_eventNotSelling_returnsEventNotSelling() {
        var slice = buildSlice(new InMemoryBookingStore(), true, false);

        slice.execute(new BuyTicket.Request(UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            "STANDARD")).await().onSuccess(response -> fail("Expected EventNotSelling")).onFailure(cause -> assertThat(cause.message()).contains("not currently selling"));
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
                                            "STANDARD")).await().onSuccess(response -> fail("Expected PaymentDeclined")).onFailure(cause -> assertThat(cause.message()).contains("declined"));
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

        slice.execute(new BuyTicket.Request(customer.toString(),
                                            event.toString(),
                                            seat.toString(),
                                            "STANDARD")).await().onSuccess(response -> fail("Expected store failure")).onFailure(cause -> assertThat(cause.message()).contains("unavailable"));
        assertThat(gateway.calls()).contains("/authorize", "/void");
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("cancelled");
        store.activeBookingCount(customer).await().onFailure(cause -> fail(cause.message())).onSuccess(count -> assertThat(count.longValue()).isZero());
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
                                            "STANDARD")).await().onSuccess(response -> fail("Expected CustomerIneligible")).onFailure(cause -> assertThat(cause.message()).contains("active bookings"));
    }

    @Test
    void execute_priceLookupFails_returnsPriceUnavailable() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, new FakeGateway(true), failingQuote, true);

        slice.execute(new BuyTicket.Request(UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            "STANDARD")).await().onSuccess(response -> fail("Expected PriceUnavailable")).onFailure(cause -> assertThat(cause.message()).contains("price is available"));
    }

    @Test
    void execute_gatewayUnavailable_returnsPaymentProviderUnavailable() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, FakeGateway.failing("/authorize"), quotePrice, true);
        var seat = UUID.randomUUID();

        slice.execute(new BuyTicket.Request(UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            seat.toString(),
                                            "STANDARD")).await().onSuccess(response -> fail("Expected PaymentProviderUnavailable")).onFailure(cause -> assertThat(cause.message()).contains("provider is unavailable"));
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("cancelled");
    }

    @Test
    void execute_storeCountFails_returnsStoreUnavailable() {
        var store = new FailingBookingStore(FailingBookingStore.FailOp.ACTIVE_BOOKING_COUNT);
        var slice = buildSlice(store, new FakeGateway(true), quotePrice, true);

        slice.execute(new BuyTicket.Request(UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            UUID.randomUUID().toString(),
                                            "STANDARD")).await().onSuccess(response -> fail("Expected StoreUnavailable")).onFailure(cause -> assertThat(cause.message()).contains("store is unavailable"));
    }

    @Test
    void validBuy_malformedCustomer_returnsFailure() {
        BuyTicket.ValidBuy.validBuy(new BuyTicket.Request("not-a-uuid",
                                                          UUID.randomUUID().toString(),
                                                          UUID.randomUUID().toString(),
                                                          "STANDARD")).onSuccess(valid -> fail("Expected validation failure"));
    }

    private void seedConfirmedBookings(InMemoryBookingStore store, UUID customer, int count) {
        for (int i = 0; i < count; i++) {
            store.insertBooking(UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                customer,
                                UUID.randomUUID()).await().onFailure(cause -> fail(cause.message()));
        }
    }
}
