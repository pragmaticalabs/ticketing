package org.pragmatica.example.ticketing.booking.cancellation.cancelticket;

import java.util.UUID;

import org.pragmatica.aether.resource.http.HttpClient;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.booking.BookingStore;
import org.pragmatica.example.ticketing.booking.FailingBookingStore;
import org.pragmatica.example.ticketing.booking.FakeGateway;
import org.pragmatica.example.ticketing.booking.InMemoryBookingStore;
import org.pragmatica.example.ticketing.shared.event.SeatReleased;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Integration-first tests for the cancel-ticket flow: the assembled slice runs with all business
/// logic; only the adapters (store, gateway, publisher) are faked. A confirmed booking is seeded
/// directly into the in-memory store (held -> confirmed reservation, an authorized payment, an issued
/// ticket and a booking row), mirroring the state the buy saga would have produced.
///
/// The cancellation's guarantee is per-step and retry-driven, so the tests assert the intermediate
/// states a failed attempt leaves behind, not just the happy path: money is never taken from the
/// customer's side of the ledger before it is returned, and a mid-flight store failure must leave the
/// operation re-drivable without refunding twice.
class CancelTicketTest {
    private final Publisher<SeatReleased> seatReleased = _ -> Promise.UNIT;

    /// The seeded rows a test needs to assert against.
    private record Seeded(UUID booking, UUID ticket, UUID seat, UUID customer) {}

    private CancelTicket buildSlice(BookingStore store, HttpClient gateway) {
        return CancelTicket.cancelTicket(store, gateway, seatReleased);
    }

    private CancelTicket buildSlice(InMemoryBookingStore store) {
        return buildSlice(store, new FakeGateway(true));
    }

    private Seeded seed(InMemoryBookingStore store, UUID customer) {
        var bookingId = UUID.randomUUID();
        var ticketId = UUID.randomUUID();
        var paymentId = UUID.randomUUID();
        var seat = UUID.randomUUID();
        var event = UUID.randomUUID();
        var reservationId = store.seedConfirmedReservation(seat, event, customer);

        store.insertTicket(ticketId, bookingId, seat).await().onFailure(cause -> fail(cause.message()));
        store.insertPayment(paymentId,
                            bookingId,
                            "authorized",
                            UUID.randomUUID(),
                            2500,
                            "USD")
             .await()
             .onFailure(cause -> fail(cause.message()));
        store.insertBooking(bookingId, reservationId, seat, event, customer, ticketId)
             .await()
             .onFailure(cause -> fail(cause.message()));

        return new Seeded(bookingId, ticketId, seat, customer);
    }

    private Seeded seed(InMemoryBookingStore store) {
        return seed(store, UUID.randomUUID());
    }

    @Test
    void execute_ownConfirmedBooking_succeeds() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var seeded = seed(store);

        slice.execute(new CancelTicket.Request(seeded.booking().toString(),
                                               seeded.customer().toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> {
                            assertThat(response.booking()).isEqualTo(seeded.booking().toString());
                            assertThat(response.receipt()).isNotBlank();
                        });
        assertThat(store.bookingStatus(seeded.booking())).isEqualTo("cancelled");
        assertThat(store.reservationStateBySeat(seeded.seat())).isEqualTo("cancelled");
        assertThat(store.ticketStatus(seeded.ticket())).isEqualTo("invalidated");
        assertThat(store.paymentStatus(seeded.booking())).isEqualTo("refunded");
    }

    @Test
    void execute_otherCustomer_returnsNotOwner() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var seeded = seed(store);

        slice.execute(new CancelTicket.Request(seeded.booking().toString(),
                                               UUID.randomUUID().toString()))
             .await()
             .onSuccess(response -> fail("Expected NotOwner"))
             .onFailure(cause -> assertThat(cause.message()).contains("another customer"));
    }

    @Test
    void execute_unknownBooking_returnsBookingNotFound() {
        var slice = buildSlice(new InMemoryBookingStore());

        slice.execute(new CancelTicket.Request(UUID.randomUUID().toString(),
                                               UUID.randomUUID().toString()))
             .await()
             .onSuccess(response -> fail("Expected BookingNotFound"))
             .onFailure(cause -> assertThat(cause.message()).contains("not found"));
    }

    @Test
    void execute_alreadyCancelledBooking_returnsAlreadyCancelled() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var seeded = seed(store);

        store.cancelBooking(seeded.booking()).await().onFailure(cause -> fail(cause.message()));
        slice.execute(new CancelTicket.Request(seeded.booking().toString(),
                                               seeded.customer().toString()))
             .await()
             .onSuccess(response -> fail("Expected AlreadyCancelled"))
             .onFailure(cause -> assertThat(cause.message()).contains("already cancelled"));
    }

    @Test
    void execute_refundRejected_returnsRefundFailed() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store, FakeGateway.failing("/refund"));
        var seeded = seed(store);

        slice.execute(new CancelTicket.Request(seeded.booking().toString(),
                                               seeded.customer().toString()))
             .await()
             .onSuccess(response -> fail("Expected RefundFailed"))
             .onFailure(cause -> assertThat(cause.message()).contains("Refund could not be completed"));
    }

    // C3: a refund failure must not have consumed the ticket first. Nothing may be written before the
    // money is back, so the post-state of a failed refund is the untouched pre-state -- never "seat
    // released and money not refunded".
    @Test
    void execute_refundFails_leavesBookingIntactAndRetryable() {
        var store = new InMemoryBookingStore();
        var seeded = seed(store);

        buildSlice(store,
                   FakeGateway.failing("/refund")).execute(new CancelTicket.Request(seeded.booking().toString(),
                                                                                    seeded.customer().toString()))
                  .await()
                  .onSuccess(response -> fail("Expected RefundFailed"))
                  .onFailure(cause -> assertThat(cause.message()).contains("Refund could not be completed"));
        assertThat(store.bookingStatus(seeded.booking())).isEqualTo("confirmed");
        assertThat(store.reservationStateBySeat(seeded.seat())).isEqualTo("confirmed");
        assertThat(store.ticketStatus(seeded.ticket())).isEqualTo("issued");
        buildSlice(store).execute(new CancelTicket.Request(seeded.booking().toString(),
                                                           seeded.customer().toString()))
                  .await()
                  .onFailure(cause -> fail("Cancellation must be retryable after a refund failure: " + cause.message()))
                  .onSuccess(response -> assertThat(response.receipt()).isNotBlank());
        assertThat(store.bookingStatus(seeded.booking())).isEqualTo("cancelled");
    }

    // C3: a store failure AFTER a successful refund must stay re-drivable, and the re-drive must not
    // refund a second time -- the recorded refund is the idempotency key.
    @Test
    void execute_retryAfterTicketInvalidationFailure_completesWithoutSecondRefund() {
        var store = new FailingBookingStore(FailingBookingStore.FailOp.INVALIDATE_TICKET);
        var gateway = new FakeGateway(true);
        var seeded = seed(store);
        var request = new CancelTicket.Request(seeded.booking().toString(),
                                               seeded.customer().toString());

        buildSlice(store, gateway).execute(request)
                  .await()
                  .onSuccess(response -> fail("Expected StoreUnavailable"))
                  .onFailure(cause -> assertThat(cause.message()).contains("store is unavailable"));
        assertThat(gateway.callCount("/refund")).isEqualTo(1);
        buildSlice(store.heal(),
                   gateway).execute(request)
                  .await()
                  .onFailure(cause -> fail("Cancellation must be re-drivable after a "
                                          + "post-refund store failure: " + cause.message()))
                  .onSuccess(response -> assertThat(response.receipt()).isNotBlank());
        assertThat(gateway.callCount("/refund")).isEqualTo(1);
        assertThat(store.bookingStatus(seeded.booking())).isEqualTo("cancelled");
        assertThat(store.reservationStateBySeat(seeded.seat())).isEqualTo("cancelled");
        assertThat(store.ticketStatus(seeded.ticket())).isEqualTo("invalidated");
    }

    // The refund and every write have already succeeded by the time the fact is published, and
    // AlreadyCancelled would reject a re-drive, so a publish failure must not fail the cancellation.
    @Test
    void execute_releaseFactPublishFails_stillCompletesCancellation() {
        var store = new InMemoryBookingStore();
        var seeded = seed(store);
        Publisher<SeatReleased> failingPublisher = _ -> Causes.cause("event bus down").promise();

        CancelTicket.cancelTicket(store,
                                  new FakeGateway(true),
                                  failingPublisher)
                    .execute(new CancelTicket.Request(seeded.booking().toString(),
                                                      seeded.customer().toString()))
                    .await()
                    .onFailure(cause -> fail("A completed cancellation must not fail on fact publish: " + cause.message()))
                    .onSuccess(response -> assertThat(response.receipt()).isNotBlank());
        assertThat(store.bookingStatus(seeded.booking())).isEqualTo("cancelled");
        assertThat(store.paymentStatus(seeded.booking())).isEqualTo("refunded");
    }

    @Test
    void execute_storeFindFails_returnsStoreUnavailable() {
        var slice = buildSlice(new FailingBookingStore(FailingBookingStore.FailOp.FIND_BOOKING), new FakeGateway(true));

        slice.execute(new CancelTicket.Request(UUID.randomUUID().toString(),
                                               UUID.randomUUID().toString()))
             .await()
             .onSuccess(response -> fail("Expected StoreUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("store is unavailable"));
    }

    @Test
    void validCancel_malformedBooking_returnsFailure() {
        CancelTicket.ValidCancel.validCancel(new CancelTicket.Request("not-a-uuid",
                                                                      UUID.randomUUID().toString())).onSuccess(valid -> fail("Expected validation failure"));
    }
}
