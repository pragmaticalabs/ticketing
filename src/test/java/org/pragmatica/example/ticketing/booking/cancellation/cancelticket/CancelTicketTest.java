package org.pragmatica.example.ticketing.booking.cancellation.cancelticket;

import org.pragmatica.aether.resource.http.HttpClient;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.booking.BookingStore;
import org.pragmatica.example.ticketing.booking.FailingBookingStore;
import org.pragmatica.example.ticketing.booking.FakeGateway;
import org.pragmatica.example.ticketing.booking.InMemoryBookingStore;
import org.pragmatica.example.ticketing.shared.event.SeatReleased;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Integration-first tests for the cancel-ticket flow: the assembled slice runs with all business
/// logic; only the adapters (store, gateway, publisher) are faked. A confirmed booking is seeded
/// directly into the in-memory store (held -> confirmed reservation plus a booking row), mirroring
/// the state the buy saga would have produced.
class CancelTicketTest {
    private final Publisher<SeatReleased> seatReleased = _ -> Promise.UNIT;

    private CancelTicket buildSlice(BookingStore store, HttpClient gateway) {
        return CancelTicket.cancelTicket(store, gateway, seatReleased);
    }

    private CancelTicket buildSlice(InMemoryBookingStore store) {
        return buildSlice(store, new FakeGateway(true));
    }

    private String seedConfirmedBooking(InMemoryBookingStore store, String customer) {
        var reservationId = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var ticketId = UUID.randomUUID();
        var seat = UUID.randomUUID();
        var event = UUID.randomUUID();
        var customerUuid = UUID.fromString(customer);

        store.claimSeat(reservationId, seat, event, customerUuid).await().onFailure(cause -> fail(cause.message()));
        store.confirmReservation(reservationId).await().onFailure(cause -> fail(cause.message()));
        store.insertBooking(bookingId, reservationId, seat, event, customerUuid, ticketId).await().onFailure(cause -> fail(cause.message()));

        return bookingId.toString();
    }

    @Test
    void execute_ownConfirmedBooking_succeeds() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var customer = UUID.randomUUID().toString();
        var booking = seedConfirmedBooking(store, customer);

        slice.execute(new CancelTicket.Request(booking, customer)).await().onFailure(cause -> fail(cause.message())).onSuccess(response -> {
            assertThat(response.booking()).isEqualTo(booking);
            assertThat(response.receipt()).isNotBlank();
        });
    }

    @Test
    void execute_otherCustomer_returnsNotOwner() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var booking = seedConfirmedBooking(store,
                                           UUID.randomUUID().toString());

        slice.execute(new CancelTicket.Request(booking,
                                               UUID.randomUUID().toString())).await().onSuccess(response -> fail("Expected NotOwner")).onFailure(cause -> assertThat(cause.message()).contains("another customer"));
    }

    @Test
    void execute_unknownBooking_returnsBookingNotFound() {
        var slice = buildSlice(new InMemoryBookingStore());

        slice.execute(new CancelTicket.Request(UUID.randomUUID().toString(),
                                               UUID.randomUUID().toString())).await().onSuccess(response -> fail("Expected BookingNotFound")).onFailure(cause -> assertThat(cause.message()).contains("not found"));
    }

    @Test
    void execute_alreadyCancelledBooking_returnsAlreadyCancelled() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var customer = UUID.randomUUID().toString();
        var booking = seedConfirmedBooking(store, customer);

        store.cancelBooking(UUID.fromString(booking)).await().onFailure(cause -> fail(cause.message()));
        slice.execute(new CancelTicket.Request(booking, customer)).await().onSuccess(response -> fail("Expected AlreadyCancelled")).onFailure(cause -> assertThat(cause.message()).contains("already cancelled"));
    }

    @Test
    void execute_refundRejected_returnsRefundFailed() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, FakeGateway.failing("/refund"));
        var customer = UUID.randomUUID().toString();
        var booking = seedConfirmedBooking(store, customer);

        slice.execute(new CancelTicket.Request(booking, customer)).await().onSuccess(response -> fail("Expected RefundFailed")).onFailure(cause -> assertThat(cause.message()).contains("Refund could not be completed"));
    }

    @Test
    void execute_storeFindFails_returnsStoreUnavailable() {
        var slice = buildSlice(new FailingBookingStore(FailingBookingStore.FailOp.FIND_BOOKING),
                               new FakeGateway(true));

        slice.execute(new CancelTicket.Request(UUID.randomUUID().toString(),
                                               UUID.randomUUID().toString())).await().onSuccess(response -> fail("Expected StoreUnavailable")).onFailure(cause -> assertThat(cause.message()).contains("store is unavailable"));
    }

    @Test
    void validCancel_malformedBooking_returnsFailure() {
        CancelTicket.ValidCancel.validCancel(new CancelTicket.Request("not-a-uuid",
                                                                      UUID.randomUUID().toString())).onSuccess(valid -> fail("Expected validation failure"));
    }
}
