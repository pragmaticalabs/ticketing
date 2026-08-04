package org.pragmatica.example.ticketing.eventmanagement.capacity.blockseat;

import java.util.UUID;

import org.pragmatica.example.ticketing.eventmanagement.FailingEventStore;
import org.pragmatica.example.ticketing.eventmanagement.InMemoryEventStore;
import org.pragmatica.example.ticketing.eventmanagement.capacity.blockseat.BlockSeat.Request;
import org.pragmatica.example.ticketing.shared.SeatState;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class BlockSeatTest {
    private final InMemoryEventStore store = new InMemoryEventStore();
    private final BlockSeat slice = BlockSeat.blockSeat(store);
    private final UUID event = UUID.randomUUID();

    private UUID availableSeat() {
        var seat = UUID.randomUUID();

        store.insertEvent(event, "Wembley Arena", "2026-07-01T19:00:00Z").await();
        store.insertSeat(seat, event, "A", "12", 7, "STANDARD").await();

        return seat;
    }

    @Test
    void execute_availableSeat_blocks() {
        var seat = availableSeat();

        slice.execute(new Request(seat.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.seat()).isEqualTo(seat.toString()));
        store.seatStateOf(seat)
             .onEmpty(() -> fail("Expected seat " + seat + " to exist"))
             .onPresent(state -> assertThat(state).isEqualTo(SeatState.BLOCKED));
    }

    @Test
    void execute_soldSeat_returnsSeatUnavailable() {
        var seat = availableSeat();

        store.markSeatSold(1L, seat).await();
        slice.execute(new Request(seat.toString()))
             .await()
             .onSuccess(_ -> fail("Expected SeatUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("not available"));
    }

    @Test
    void execute_unknownSeat_returnsSeatUnavailable() {
        slice.execute(new Request(UUID.randomUUID().toString()))
             .await()
             .onSuccess(_ -> fail("Expected SeatUnavailable"))
             .onFailure(cause -> assertThat(cause.message()).contains("not available"));
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
        var failing = BlockSeat.blockSeat(FailingEventStore.allOperationsFail());

        failing.execute(new Request(UUID.randomUUID().toString()))
               .await()
               .onSuccess(_ -> fail("Expected StoreUnavailable"))
               .onFailure(cause -> assertThat(cause.message()).contains("unavailable"));
    }
}
