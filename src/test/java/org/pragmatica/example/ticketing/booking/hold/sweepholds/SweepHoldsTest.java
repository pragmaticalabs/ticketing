package org.pragmatica.example.ticketing.booking.hold.sweepholds;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.booking.FailingBookingStore;
import org.pragmatica.example.ticketing.booking.InMemoryBookingStore;
import org.pragmatica.example.ticketing.shared.event.SeatReleased;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Integration-first test for the sweep-holds use case: the Iteration expires held rows and publishes
/// a SeatReleased fact per freed seat, returning the count.
class SweepHoldsTest {
    private final Publisher<SeatReleased> seatReleased = _ -> Promise.UNIT;

    @Test
    void execute_heldSeats_releasesAndCounts() {
        var store = new InMemoryBookingStore();
        var slice = SweepHolds.sweepHolds(store, seatReleased);

        store.claimSeat(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).await().onFailure(cause -> fail(cause.message()));
        store.claimSeat(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).await().onFailure(cause -> fail(cause.message()));
        slice.execute(new SweepHolds.Request()).await().onFailure(cause -> fail(cause.message())).onSuccess(response -> assertThat(response.released()).isEqualTo(2));
    }

    @Test
    void execute_noHeldSeats_releasesNone() {
        var slice = SweepHolds.sweepHolds(new InMemoryBookingStore(), seatReleased);

        slice.execute(new SweepHolds.Request()).await().onFailure(cause -> fail(cause.message())).onSuccess(response -> assertThat(response.released()).isZero());
    }

    @Test
    void execute_storeExpireFails_returnsStoreUnavailable() {
        var slice = SweepHolds.sweepHolds(new FailingBookingStore(FailingBookingStore.FailOp.EXPIRE_HOLDS), seatReleased);

        slice.execute(new SweepHolds.Request()).await().onSuccess(response -> fail("Expected StoreUnavailable")).onFailure(cause -> assertThat(cause.message()).contains("store is unavailable"));
    }
}
