package org.pragmatica.example.ticketing.booking.hold.checkhold;

import org.pragmatica.example.ticketing.booking.InMemoryBookingStore;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Integration-first tests for the check-hold use case: the assembled slice maps the persisted
/// reservation state plus the time-as-decay flags to a single label. A held seat reads FRESH; an
/// unknown seat reads NONE.
class CheckHoldTest {
    private CheckHold buildSlice(InMemoryBookingStore store) {
        return CheckHold.checkHold(store);
    }

    @Test
    void execute_heldSeat_returnsFresh() {
        var store = new InMemoryBookingStore();
        var slice = buildSlice(store);
        var seat = UUID.randomUUID().toString();

        store.claimSeat(UUID.randomUUID(), UUID.fromString(seat), UUID.randomUUID(), UUID.randomUUID()).await().onFailure(cause -> fail(cause.message()));
        slice.execute(new CheckHold.Request(seat)).await().onFailure(cause -> fail(cause.message())).onSuccess(response -> {
            assertThat(response.seat()).isEqualTo(seat);
            assertThat(response.state()).isEqualTo("FRESH");
        });
    }

    @Test
    void execute_staleHold_returnsStale() {
        var store = new InMemoryBookingStore().withDecay(InMemoryBookingStore.Decay.STALE);
        var slice = buildSlice(store);
        var seat = UUID.randomUUID().toString();

        store.claimSeat(UUID.randomUUID(), UUID.fromString(seat), UUID.randomUUID(), UUID.randomUUID()).await().onFailure(cause -> fail(cause.message()));
        slice.execute(new CheckHold.Request(seat)).await().onFailure(cause -> fail(cause.message())).onSuccess(response -> assertThat(response.state()).isEqualTo("STALE"));
    }

    @Test
    void execute_expiredHold_returnsExpired() {
        var store = new InMemoryBookingStore().withDecay(InMemoryBookingStore.Decay.EXPIRED);
        var slice = buildSlice(store);
        var seat = UUID.randomUUID().toString();

        store.claimSeat(UUID.randomUUID(), UUID.fromString(seat), UUID.randomUUID(), UUID.randomUUID()).await().onFailure(cause -> fail(cause.message()));
        slice.execute(new CheckHold.Request(seat)).await().onFailure(cause -> fail(cause.message())).onSuccess(response -> assertThat(response.state()).isEqualTo("EXPIRED"));
    }

    @Test
    void execute_unknownSeat_returnsNone() {
        var slice = buildSlice(new InMemoryBookingStore());

        slice.execute(new CheckHold.Request(UUID.randomUUID().toString())).await().onFailure(cause -> fail(cause.message())).onSuccess(response -> assertThat(response.state()).isEqualTo("NONE"));
    }
}
