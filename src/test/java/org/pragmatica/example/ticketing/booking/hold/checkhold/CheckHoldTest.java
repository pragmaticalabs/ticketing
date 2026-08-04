package org.pragmatica.example.ticketing.booking.hold.checkhold;

import java.util.UUID;

import org.pragmatica.example.ticketing.booking.InMemoryBookingStore;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Integration-first tests for the check-hold use case: the assembled slice maps the persisted
/// reservation state plus the time-as-decay flags to a single label. The decay flags only describe a
/// live hold, so the label must be driven by the reservation's state first: a held seat reads
/// FRESH/STALE/EXPIRED by its TTL, a sold seat reads SOLD, and a seat whose reservation is cancelled
/// (or has none at all) reads NONE.
class CheckHoldTest {
    private CheckHold buildSlice(InMemoryBookingStore store) {
        return CheckHold.checkHold(store);
    }

    @Test
    void execute_heldSeat_returnsFresh() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var seat = UUID.randomUUID();

        store.seedHold(seat, UUID.randomUUID(), UUID.randomUUID());
        slice.execute(new CheckHold.Request(seat.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> {
                            assertThat(response.seat()).isEqualTo(seat.toString());
                            assertThat(response.state()).isEqualTo("FRESH");
                        });
    }

    @Test
    void execute_staleHold_returnsStale() {
        var store = new InMemoryBookingStore().withDecay(InMemoryBookingStore.Decay.STALE);
        var slice = buildSlice(store);
        var seat = UUID.randomUUID();

        store.seedHold(seat, UUID.randomUUID(), UUID.randomUUID());
        slice.execute(new CheckHold.Request(seat.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.state()).isEqualTo("STALE"));
    }

    @Test
    void execute_expiredHold_returnsExpired() {
        var store = new InMemoryBookingStore().withDecay(InMemoryBookingStore.Decay.EXPIRED);
        var slice = buildSlice(store);
        var seat = UUID.randomUUID();

        store.seedHold(seat, UUID.randomUUID(), UUID.randomUUID());
        slice.execute(new CheckHold.Request(seat.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.state()).isEqualTo("EXPIRED"));
    }

    @Test
    void execute_unknownSeat_returnsNone() {
        var slice = buildSlice(new InMemoryBookingStore());

        slice.execute(new CheckHold.Request(UUID.randomUUID().toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.state()).isEqualTo("NONE"));
    }

    // H4: a cancelled reservation keeps whatever TTL it had, so a state-blind read reports a live
    // hold on a seat that is in fact free.
    @Test
    void execute_cancelledReservation_returnsNone() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var seat = UUID.randomUUID();
        var reservation = store.seedHold(seat, UUID.randomUUID(), UUID.randomUUID());

        store.releaseReservation(reservation).await().onFailure(cause -> fail(cause.message()));
        slice.execute(new CheckHold.Request(seat.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.state()).isEqualTo("NONE"));
    }

    // H4: a confirmed reservation has no expiry at all, so the decay flags are SQL NULL. The read must
    // report the seat as sold rather than failing on a NULL bound to a primitive.
    @Test
    void execute_confirmedReservation_returnsSold() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var seat = UUID.randomUUID();

        store.seedConfirmedReservation(seat, UUID.randomUUID(), UUID.randomUUID());
        slice.execute(new CheckHold.Request(seat.toString()))
             .await()
             .onFailure(cause -> fail("A confirmed reservation must be readable: " + cause.message()))
             .onSuccess(response -> assertThat(response.state()).isEqualTo("SOLD"));
    }
}
