package org.pragmatica.example.ticketing.eventmanagement.capacity.blockseat;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.eventmanagement.EventStore.EventRow;
import org.pragmatica.example.ticketing.eventmanagement.EventStore.RowId;
import org.pragmatica.example.ticketing.eventmanagement.capacity.blockseat.BlockSeat.Request;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class BlockSeatTest {
    // In-memory fake of the @PgSql store. The guarded block mirrors the SQL `... WHERE status =
    // 'available' RETURNING id`: an out-of-state seat yields an empty projection.
    private static final class FakeStore implements EventStore {
        private final Map<UUID, EventRow> events = new HashMap<>();
        private final Map<UUID, String> seats = new HashMap<>();

        @Override
        public Promise<Unit> insertEvent(UUID id, String venue, String onSaleAt) {
            events.put(id, new EventRow("draft", onSaleAt));

            return Promise.UNIT;
        }

        @Override
        public Promise<Boolean> eventExists(UUID id) {
            return Promise.success(events.containsKey(id));
        }

        @Override
        public Promise<Unit> insertSeat(UUID id,
                                        UUID eventId,
                                        String section,
                                        String seatRow,
                                        int number,
                                        String tier) {
            seats.put(id, "available");

            return Promise.UNIT;
        }

        @Override
        public Promise<Option<RowId>> openEvent(UUID id) {
            return Promise.success(Option.empty());
        }

        @Override
        public Promise<Option<RowId>> cancelEvent(UUID id) {
            return Promise.success(Option.empty());
        }

        @Override
        public Promise<Option<RowId>> blockSeat(UUID id) {
            return transitionSeat(id, "available", "blocked");
        }

        @Override
        public Promise<Option<RowId>> releaseSeat(UUID id) {
            return transitionSeat(id, "blocked", "available");
        }

        @Override
        public Promise<Option<EventRow>> findEvent(UUID id) {
            return Promise.success(Option.option(events.get(id)));
        }

        @Override
        public Promise<Unit> markSeatSold(UUID id) {
            return Promise.UNIT;
        }

        @Override
        public Promise<Unit> markSeatAvailable(UUID id) {
            return Promise.UNIT;
        }

        private Promise<Option<RowId>> transitionSeat(UUID id, String from, String to) {
            var status = seats.get(id);

            if (status == null || !status.equals(from)) {
                return Promise.success(Option.empty());
            }

            seats.put(id, to);

            return Promise.success(Option.present(new RowId(id)));
        }
    }

    // In-memory fake whose every operation fails, simulating a store outage. Used to prove the slice
    // maps a store failure onto its own typed StoreUnavailable.
    private static final class FailingStore implements EventStore {
        private static final Cause STORE_DOWN = Causes.cause("simulated store outage");

        @Override
        public Promise<Unit> insertEvent(UUID id, String venue, String onSaleAt) {
            return STORE_DOWN.promise();
        }

        @Override
        public Promise<Boolean> eventExists(UUID id) {
            return STORE_DOWN.promise();
        }

        @Override
        public Promise<Unit> insertSeat(UUID id,
                                        UUID eventId,
                                        String section,
                                        String seatRow,
                                        int number,
                                        String tier) {
            return STORE_DOWN.promise();
        }

        @Override
        public Promise<Option<RowId>> openEvent(UUID id) {
            return STORE_DOWN.promise();
        }

        @Override
        public Promise<Option<RowId>> cancelEvent(UUID id) {
            return STORE_DOWN.promise();
        }

        @Override
        public Promise<Option<RowId>> blockSeat(UUID id) {
            return STORE_DOWN.promise();
        }

        @Override
        public Promise<Option<RowId>> releaseSeat(UUID id) {
            return STORE_DOWN.promise();
        }

        @Override
        public Promise<Option<EventRow>> findEvent(UUID id) {
            return STORE_DOWN.promise();
        }

        @Override
        public Promise<Unit> markSeatSold(UUID id) {
            return STORE_DOWN.promise();
        }

        @Override
        public Promise<Unit> markSeatAvailable(UUID id) {
            return STORE_DOWN.promise();
        }
    }

    private final FakeStore store = new FakeStore();
    private final BlockSeat slice = BlockSeat.blockSeat(store);

    @Test
    void execute_availableSeat_blocks() {
        var seat = UUID.randomUUID();

        store.insertSeat(seat, UUID.randomUUID(), "A", "12", 7, "STANDARD").await();
        slice.execute(new Request(seat.toString())).await().onFailure(cause -> fail(cause.message())).onSuccess(response -> assertThat(response.seat()).isEqualTo(seat.toString()));
    }

    @Test
    void execute_unknownSeat_returnsSeatUnavailable() {
        slice.execute(new Request(UUID.randomUUID().toString())).await().onSuccess(response -> fail("Expected SeatUnavailable")).onFailure(cause -> assertThat(cause.message()).contains("not available"));
    }

    @Test
    void execute_malformedSeat_returnsValidationFailure() {
        slice.execute(new Request("not-a-uuid")).await().onSuccess(response -> fail("Expected validation failure")).onFailure(cause -> assertThat(cause.message()).contains("valid UUID"));
    }

    @Test
    void execute_storeFails_returnsStoreUnavailable() {
        var failing = BlockSeat.blockSeat(new FailingStore());

        failing.execute(new Request(UUID.randomUUID().toString())).await().onSuccess(response -> fail("Expected StoreUnavailable")).onFailure(cause -> assertThat(cause.message()).contains("unavailable"));
    }
}
