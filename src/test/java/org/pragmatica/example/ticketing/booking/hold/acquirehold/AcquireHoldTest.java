package org.pragmatica.example.ticketing.booking.hold.acquirehold;

import org.pragmatica.example.ticketing.booking.FailingBookingStore;
import org.pragmatica.example.ticketing.booking.InMemoryBookingStore;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Integration-first tests for the acquire-hold use case: the assembled slice runs over the
/// in-memory store. The design-out seat claim refuses a contended seat with SeatUnavailable.
class AcquireHoldTest {
    private AcquireHold buildSlice(InMemoryBookingStore store) {
        return AcquireHold.acquireHold(store);
    }

    @Test
    void execute_freeSeat_returnsFreshHold() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var seat = UUID.randomUUID().toString();

        slice.execute(new AcquireHold.Request(UUID.randomUUID().toString(),
                                              UUID.randomUUID().toString(),
                                              seat)).await().onFailure(cause -> fail(cause.message())).onSuccess(response -> {
            assertThat(response.state()).isEqualTo("FRESH");
            assertThat(response.reservation()).isNotBlank();
        });
    }

    @Test
    void execute_seatAlreadyHeld_returnsSeatUnavailable() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var seat = UUID.randomUUID().toString();

        store.claimSeat(UUID.randomUUID(), UUID.fromString(seat), UUID.randomUUID(), UUID.randomUUID()).await().onFailure(cause -> fail(cause.message()));
        slice.execute(new AcquireHold.Request(UUID.randomUUID().toString(),
                                              UUID.randomUUID().toString(),
                                              seat)).await().onSuccess(response -> fail("Expected SeatUnavailable")).onFailure(cause -> assertThat(cause.message()).contains("no longer available"));
    }

    @Test
    void execute_storeClaimFails_returnsStoreUnavailable() {
        var slice = buildSlice(new FailingBookingStore(FailingBookingStore.FailOp.CLAIM_SEAT));

        slice.execute(new AcquireHold.Request(UUID.randomUUID().toString(),
                                              UUID.randomUUID().toString(),
                                              UUID.randomUUID().toString())).await().onSuccess(response -> fail("Expected StoreUnavailable")).onFailure(cause -> assertThat(cause.message()).contains("store is unavailable"));
    }

    @Test
    void validAcquire_malformedCustomer_returnsFailure() {
        AcquireHold.ValidAcquire.validAcquire(new AcquireHold.Request("not-a-uuid",
                                                                      UUID.randomUUID().toString(),
                                                                      UUID.randomUUID().toString())).onSuccess(valid -> fail("Expected validation failure"));
    }
}
