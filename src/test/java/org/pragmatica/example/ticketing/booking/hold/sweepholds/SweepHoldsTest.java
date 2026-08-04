package org.pragmatica.example.ticketing.booking.hold.sweepholds;

import java.util.UUID;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.booking.FailingBookingStore;
import org.pragmatica.example.ticketing.booking.InMemoryBookingStore;
import org.pragmatica.example.ticketing.shared.event.SeatReleased;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Integration-first test for the sweep-holds use case: the Iteration expires the reservations that
/// time has invalidated and publishes a SeatReleased fact per freed seat, returning the count.
///
/// Two kinds of row decay with time and must be swept: a hold whose TTL has passed, and a
/// confirmation that never became a booking (the crash window between confirming a reservation and
/// writing its booking row). Both leave a seat that no live process will ever free, so both are
/// reaped here; a hold that is still fresh must be left alone.
class SweepHoldsTest {
    private final Publisher<SeatReleased> seatReleased = _ -> Promise.UNIT;

    @Test
    void execute_expiredHolds_releasesAndCounts() {
        var store = new InMemoryBookingStore().withDecay(InMemoryBookingStore.Decay.EXPIRED);
        var slice = SweepHolds.sweepHolds(store, seatReleased);

        store.seedHold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        store.seedHold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        slice.execute(new SweepHolds.Request())
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.released()).isEqualTo(2));
    }

    @Test
    void execute_freshHolds_releasesNone() {
        var store = new InMemoryBookingStore();
        var slice = SweepHolds.sweepHolds(store, seatReleased);
        var seat = UUID.randomUUID();

        store.seedHold(seat, UUID.randomUUID(), UUID.randomUUID());
        slice.execute(new SweepHolds.Request())
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.released()).isZero());
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("held");
    }

    @Test
    void execute_noHeldSeats_releasesNone() {
        var slice = SweepHolds.sweepHolds(new InMemoryBookingStore(), seatReleased);

        slice.execute(new SweepHolds.Request())
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.released()).isZero());
    }

    // H3: a crash between confirming a reservation and writing its booking row leaves a confirmed
    // reservation no reaper reclaims and no claim guard admits -- the seat is lost forever.
    @Test
    void execute_orphanedConfirmedReservation_releasesSeat() {
        var store = new InMemoryBookingStore().withAgedClaims();
        var slice = SweepHolds.sweepHolds(store, seatReleased);
        var seat = UUID.randomUUID();

        store.seedConfirmedReservation(seat, UUID.randomUUID(), UUID.randomUUID());
        slice.execute(new SweepHolds.Request())
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.released()).isEqualTo(1));
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("cancelled");
    }

    // The orphan reaper is bounded by age so it can never race a purchase that is mid-flight between
    // confirming its reservation and writing its booking row.
    @Test
    void execute_recentConfirmedReservation_isNotReaped() {
        var store = new InMemoryBookingStore();
        var slice = SweepHolds.sweepHolds(store, seatReleased);
        var seat = UUID.randomUUID();

        store.seedConfirmedReservation(seat, UUID.randomUUID(), UUID.randomUUID());
        slice.execute(new SweepHolds.Request())
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.released()).isZero());
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("confirmed");
    }

    // A confirmed reservation that DID produce a booking is a live sale, not an orphan.
    @Test
    void execute_bookedConfirmedReservation_isNotReaped() {
        var store = new InMemoryBookingStore().withAgedClaims();
        var slice = SweepHolds.sweepHolds(store, seatReleased);
        var seat = UUID.randomUUID();
        var event = UUID.randomUUID();
        var customer = UUID.randomUUID();
        var reservation = store.seedConfirmedReservation(seat, event, customer);

        store.insertBooking(UUID.randomUUID(),
                            reservation,
                            seat,
                            event,
                            customer,
                            UUID.randomUUID())
             .await()
             .onFailure(cause -> fail(cause.message()));
        slice.execute(new SweepHolds.Request())
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.released()).isZero());
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("confirmed");
    }

    @Test
    void execute_storeExpireFails_returnsStoreUnavailable() {
        var slice = SweepHolds.sweepHolds(new FailingBookingStore(FailingBookingStore.FailOp.EXPIRE_HOLDS), seatReleased);

        slice.execute(new SweepHolds.Request())
             .await()
             .onSuccess(response -> fail("Expected StoreUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("store is unavailable"));
    }

    @Test
    void sweep_expiredHolds_expiresThemAndSucceeds() {
        var store = new InMemoryBookingStore().withDecay(InMemoryBookingStore.Decay.EXPIRED);
        var slice = SweepHolds.sweepHolds(store, seatReleased);

        store.seedHold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        slice.sweep().await().onFailure(cause -> fail(cause.message()));
        slice.execute(new SweepHolds.Request())
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.released()).isZero());
    }

    @Test
    void sweep_storeExpireFails_propagatesStoreUnavailable() {
        var slice = SweepHolds.sweepHolds(new FailingBookingStore(FailingBookingStore.FailOp.EXPIRE_HOLDS), seatReleased);

        slice.sweep()
             .await()
             .onSuccess(_ -> fail("Expected StoreUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("store is unavailable"));
    }
}
