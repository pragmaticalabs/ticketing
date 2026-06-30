package org.pragmatica.example.ticketing.availability.query.seatstatus;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.availability.query.seatstatus.SeatStatus.Request;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class SeatStatusTest {
    private static final class FakeStore implements SeatStatusStore {
        private final Map<UUID, String> statuses = new HashMap<>();

        void seed(UUID seatId, String status) {
            statuses.put(seatId, status);
        }

        @Override
        public Promise<Option<StatusRow>> findStatus(UUID seatId) {
            return Promise.success(Option.option(statuses.get(seatId)).map(StatusRow::new));
        }
    }

    // Store whose every operation fails, to simulate the availability store being unavailable.
    private static final class FailingStore implements SeatStatusStore {
        @Override
        public Promise<Option<StatusRow>> findStatus(UUID seatId) {
            return Causes.cause("store unavailable").promise();
        }
    }

    private final FakeStore store = new FakeStore();
    private final SeatStatus slice = SeatStatus.seatStatus(store);

    @Test
    void execute_seatSold_returnsSold() {
        var seat = UUID.randomUUID();

        store.seed(seat, "sold");
        slice.execute(new Request(seat.toString())).await().onFailure(cause -> fail(cause.message())).onSuccess(r -> assertThat(r.state()).isEqualTo("sold"));
    }

    @Test
    void execute_unknownSeat_returnsAvailable() {
        slice.execute(new Request(UUID.randomUUID().toString())).await().onFailure(cause -> fail(cause.message())).onSuccess(r -> assertThat(r.state()).isEqualTo("available"));
    }

    @Test
    void execute_malformedSeat_returnsError() {
        slice.execute(new Request("not-a-uuid")).await().onSuccess(r -> fail("Expected validation failure")).onFailure(cause -> assertThat(cause.message()).contains("valid UUID"));
    }

    @Test
    void execute_storeFails_returnsStoreUnavailable() {
        var failing = SeatStatus.seatStatus(new FailingStore());

        failing.execute(new Request(UUID.randomUUID().toString())).await().onSuccess(r -> fail("Expected store failure")).onFailure(cause -> assertThat(cause).isInstanceOf(SeatStatus.AvailabilityError.StoreUnavailable.class));
    }
}
