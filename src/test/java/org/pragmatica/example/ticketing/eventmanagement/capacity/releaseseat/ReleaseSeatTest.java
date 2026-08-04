package org.pragmatica.example.ticketing.eventmanagement.capacity.releaseseat;

import java.util.UUID;

import org.pragmatica.example.ticketing.eventmanagement.FailingEventStore;
import org.pragmatica.example.ticketing.eventmanagement.InMemoryEventStore;
import org.pragmatica.example.ticketing.eventmanagement.capacity.releaseseat.ReleaseSeat.Request;
import org.pragmatica.example.ticketing.shared.SeatState;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class ReleaseSeatTest {
    private final InMemoryEventStore store = new InMemoryEventStore();
    private final ReleaseSeat slice = ReleaseSeat.releaseSeat(store);
    private final UUID event = UUID.randomUUID();

    private UUID availableSeat() {
        var seat = UUID.randomUUID();

        store.insertEvent(event, "Wembley Arena", "2026-07-01T19:00:00Z").await();
        store.insertSeat(seat, event, "A", "12", 7, "STANDARD").await();

        return seat;
    }

    @Test
    void execute_blockedSeat_releases() {
        var seat = availableSeat();

        store.blockSeat(seat).await();
        slice.execute(new Request(seat.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.seat()).isEqualTo(seat.toString()));
        store.seatStateOf(seat)
             .onEmpty(() -> fail("Expected seat " + seat + " to exist"))
             .onPresent(state -> assertThat(state).isEqualTo(SeatState.AVAILABLE));
    }

    @Test
    void execute_availableSeat_returnsSeatNotBlocked() {
        var seat = availableSeat();

        slice.execute(new Request(seat.toString()))
             .await()
             .onSuccess(_ -> fail("Expected SeatNotBlocked"))
             .onFailure(cause -> assertThat(cause.message()).contains("not blocked"));
    }

    /// A sold seat must not be freed by the capacity endpoint; only the `SeatReleased` convergence may
    /// take a seat out of `sold`.
    @Test
    void execute_soldSeat_returnsSeatNotBlocked() {
        var seat = availableSeat();

        store.markSeatSold(1L, seat).await();
        slice.execute(new Request(seat.toString()))
             .await()
             .onSuccess(_ -> fail("Expected SeatNotBlocked"))
             .onFailure(cause -> assertThat(cause.message()).contains("not blocked"));
        store.seatStateOf(seat)
             .onEmpty(() -> fail("Expected seat " + seat + " to exist"))
             .onPresent(state -> assertThat(state).isEqualTo(SeatState.SOLD));
    }

    @Test
    void execute_malformedSeat_returnsValidationFailure() {
        slice.execute(new Request("not-a-uuid"))
             .await()
             .onSuccess(_ -> fail("Expected validation failure"))
             .onFailure(cause -> assertThat(cause.message()).contains("valid UUID"));
    }

    @Test
    void execute_storeFails_returnsStoreUnavailable() {
        var failing = ReleaseSeat.releaseSeat(FailingEventStore.allOperationsFail());

        failing.execute(new Request(UUID.randomUUID().toString()))
               .await()
               .onSuccess(_ -> fail("Expected StoreUnavailable"))
               .onFailure(cause -> assertThat(cause.message()).contains("unavailable"));
    }
}
