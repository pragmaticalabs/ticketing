package org.pragmatica.example.ticketing.eventmanagement.convergence.markseatsold;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.eventmanagement.EventStore.EventRow;
import org.pragmatica.example.ticketing.eventmanagement.EventStore.RowId;
import org.pragmatica.example.ticketing.shared.event.SeatSold;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class MarkSeatSoldTest {
    // In-memory fake of the @PgSql store. `markSeatSold` converges a seat to 'sold'; `seatStatusOf`
    // exposes the converged state for assertions.
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
            return Promise.success(Option.empty());
        }

        @Override
        public Promise<Option<RowId>> releaseSeat(UUID id) {
            return Promise.success(Option.empty());
        }

        @Override
        public Promise<Option<EventRow>> findEvent(UUID id) {
            return Promise.success(Option.option(events.get(id)));
        }

        @Override
        public Promise<Unit> markSeatSold(UUID id) {
            seats.put(id, "sold");

            return Promise.UNIT;
        }

        @Override
        public Promise<Unit> markSeatAvailable(UUID id) {
            return Promise.UNIT;
        }

        String seatStatusOf(UUID id) {
            return seats.get(id);
        }
    }

    // In-memory fake whose every operation fails, simulating a store outage. Used to prove the
    // best-effort subscriber recovers a transient store error to Unit instead of propagating failure.
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
    private final MarkSeatSold slice = MarkSeatSold.markSeatSold(store);

    @Test
    void execute_existingSeat_marksSold() {
        var seat = UUID.randomUUID();

        store.insertSeat(seat, UUID.randomUUID(), "A", "12", 7, "STANDARD").await();
        slice.execute(new SeatSold(seat.toString(),
                                   UUID.randomUUID().toString(),
                                   UUID.randomUUID().toString())).await().onFailure(cause -> fail(cause.message()));
        assertThat(store.seatStatusOf(seat)).isEqualTo("sold");
    }

    @Test
    void execute_malformedFact_recoversToUnit() {
        slice.execute(new SeatSold("not-a-uuid",
                                   UUID.randomUUID().toString(),
                                   UUID.randomUUID().toString())).await().onFailure(cause -> fail(cause.message()));
    }

    @Test
    void execute_storeFails_recoversToUnit() {
        var failing = MarkSeatSold.markSeatSold(new FailingStore());

        failing.execute(new SeatSold(UUID.randomUUID().toString(),
                                     UUID.randomUUID().toString(),
                                     UUID.randomUUID().toString())).await().onFailure(cause -> fail(cause.message()));
    }
}
