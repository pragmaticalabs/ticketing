package org.pragmatica.example.ticketing.eventmanagement.lifecycle.openevent;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.eventmanagement.EventStore.EventRow;
import org.pragmatica.example.ticketing.eventmanagement.EventStore.RowId;
import org.pragmatica.example.ticketing.eventmanagement.lifecycle.openevent.OpenEvent.Request;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class OpenEventTest {
    // In-memory fake of the @PgSql store. Guarded transitions mirror the SQL `... WHERE status = <from>
    // RETURNING id`: an out-of-state row yields an empty projection, exactly as the database would.
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
            return transitionEvent(id, "draft", "on_sale");
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
            return Promise.UNIT;
        }

        @Override
        public Promise<Unit> markSeatAvailable(UUID id) {
            return Promise.UNIT;
        }

        private Promise<Option<RowId>> transitionEvent(UUID id, String from, String to) {
            var existing = events.get(id);

            if (existing == null || !existing.status().equals(from)) {
                return Promise.success(Option.empty());
            }

            events.put(id, new EventRow(to, existing.onSaleAt()));

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
    private final OpenEvent slice = OpenEvent.openEvent(store);

    @Test
    void execute_draftEvent_marksOnSale() {
        var id = UUID.randomUUID();

        store.insertEvent(id, "Wembley Arena", "2026-07-01T19:00:00Z").await();
        slice.execute(new Request(id.toString())).await().onFailure(cause -> fail(cause.message())).onSuccess(response -> assertThat(response.event()).isEqualTo(id.toString()));
    }

    @Test
    void execute_unknownEvent_returnsEventNotFound() {
        slice.execute(new Request(UUID.randomUUID().toString())).await().onSuccess(response -> fail("Expected EventNotFound")).onFailure(cause -> assertThat(cause.message()).contains("not found"));
    }

    @Test
    void execute_alreadyOpenEvent_returnsAlreadyOpen() {
        var id = UUID.randomUUID();

        store.insertEvent(id, "Wembley Arena", "2026-07-01T19:00:00Z").await();
        slice.execute(new Request(id.toString())).await();
        slice.execute(new Request(id.toString())).await().onSuccess(response -> fail("Expected AlreadyOpen")).onFailure(cause -> assertThat(cause.message()).contains("already open"));
    }

    @Test
    void execute_malformedId_returnsValidationFailure() {
        slice.execute(new Request("not-a-uuid")).await().onSuccess(response -> fail("Expected validation failure")).onFailure(cause -> assertThat(cause.message()).contains("valid UUID"));
    }

    @Test
    void execute_storeFails_returnsStoreUnavailable() {
        var failing = OpenEvent.openEvent(new FailingStore());

        failing.execute(new Request(UUID.randomUUID().toString())).await().onSuccess(response -> fail("Expected StoreUnavailable")).onFailure(cause -> assertThat(cause.message()).contains("unavailable"));
    }
}
