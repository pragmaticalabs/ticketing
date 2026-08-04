package org.pragmatica.example.ticketing.booking.hold.acquirehold;

import java.util.UUID;

import org.pragmatica.example.ticketing.booking.FailingBookingStore;
import org.pragmatica.example.ticketing.booking.InMemoryBookingStore;
import org.pragmatica.example.ticketing.eventmanagement.capacity.seatsellability.SeatSellability;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Integration-first tests for the acquire-hold use case: the assembled slice runs over the
/// in-memory store. The design-out seat claim refuses a seat another customer holds or has bought,
/// but a customer re-acquiring their OWN live hold simply refreshes it -- the same rule that lets a
/// hold become a purchase.
class AcquireHoldTest {
    // Seat-state gate stubs. The `seats` table is owned by event-management, so the hold path reaches
    // it through this slice; a blocked seat must be refused before the claim is attempted.
    private static final SeatSellability SELLABLE = request -> Promise.success(new SeatSellability.Response(request.seat(),
                                                                                                            "available",
                                                                                                            true));

    private static final SeatSellability BLOCKED = request -> Promise.success(new SeatSellability.Response(request.seat(),
                                                                                                           "blocked",
                                                                                                           false));

    private AcquireHold buildSlice(InMemoryBookingStore store) {
        return AcquireHold.acquireHold(store, SELLABLE);
    }

    @Test
    void execute_blockedSeat_isRefused() {
        var store = new InMemoryBookingStore();
        var slice = AcquireHold.acquireHold(store, BLOCKED);

        slice.execute(new AcquireHold.Request(UUID.randomUUID().toString(),
                                              UUID.randomUUID().toString(),
                                              UUID.randomUUID().toString()))
             .await()
             .onSuccess(_ -> fail("a blocked seat must not be holdable"))
             .onFailure(cause -> assertThat(cause).isInstanceOf(AcquireHold.AcquireError.SeatNotSellable.class));
    }

    @Test
    void execute_freeSeat_returnsFreshHold() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var seat = UUID.randomUUID().toString();

        slice.execute(new AcquireHold.Request(UUID.randomUUID().toString(),
                                              UUID.randomUUID().toString(),
                                              seat))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> {
                            assertThat(response.state()).isEqualTo("FRESH");
                            assertThat(response.reservation()).isNotBlank();
                        });
    }

    @Test
    void execute_seatAlreadyHeld_returnsSeatUnavailable() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var seat = UUID.randomUUID();

        store.seedHold(seat, UUID.randomUUID(), UUID.randomUUID());
        slice.execute(new AcquireHold.Request(UUID.randomUUID().toString(),
                                              UUID.randomUUID().toString(),
                                              seat.toString()))
             .await()
             .onSuccess(response -> fail("Expected SeatUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("no longer available"));
    }

    // C2: the claim guard admits the same customer's live hold, so re-acquiring refreshes the TTL and
    // hands back the rotated claim identity instead of fast-failing.
    @Test
    void execute_ownLiveHold_refreshesHold() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var seat = UUID.randomUUID();
        var event = UUID.randomUUID();
        var customer = UUID.randomUUID();

        store.seedHold(seat, event, customer);
        slice.execute(new AcquireHold.Request(customer.toString(),
                                              event.toString(),
                                              seat.toString()))
             .await()
             .onFailure(cause -> fail("Own live hold must be refreshable: " + cause.message()))
             .onSuccess(response -> {
                            assertThat(response.state()).isEqualTo("FRESH");
                            assertThat(response.reservation()).isNotBlank();
                        });
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("held");
    }

    @Test
    void execute_seatAlreadySold_returnsSeatUnavailable() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var seat = UUID.randomUUID();
        var event = UUID.randomUUID();
        var customer = UUID.randomUUID();

        store.seedConfirmedReservation(seat, event, customer);
        slice.execute(new AcquireHold.Request(customer.toString(),
                                              event.toString(),
                                              seat.toString()))
             .await()
             .onSuccess(response -> fail("Expected SeatUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("no longer available"));
    }

    @Test
    void execute_storeClaimFails_returnsStoreUnavailable() {
        var slice = buildSlice(new FailingBookingStore(FailingBookingStore.FailOp.CLAIM_SEAT));

        slice.execute(new AcquireHold.Request(UUID.randomUUID().toString(),
                                              UUID.randomUUID().toString(),
                                              UUID.randomUUID().toString()))
             .await()
             .onSuccess(response -> fail("Expected StoreUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("store is unavailable"));
    }

    @Test
    void validAcquire_malformedCustomer_returnsFailure() {
        AcquireHold.ValidAcquire.validAcquire(new AcquireHold.Request("not-a-uuid",
                                                                      UUID.randomUUID().toString(),
                                                                      UUID.randomUUID().toString())).onSuccess(valid -> fail("Expected validation failure"));
    }
}
