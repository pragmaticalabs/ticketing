package org.pragmatica.example.ticketing.availability.query.soldcount;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.availability.query.soldcount.SoldCount.Request;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class SoldCountTest {
    private static final class FakeStore implements SoldCountStore {
        private final Map<UUID, Long> soldCounts = new HashMap<>();

        void seed(UUID eventId, long count) {
            soldCounts.put(eventId, count);
        }

        @Override
        public Promise<Long> countSold(UUID eventId) {
            return Promise.success(soldCounts.getOrDefault(eventId, 0L));
        }
    }

    // Store whose every operation fails, to simulate the availability store being unavailable.
    private static final class FailingStore implements SoldCountStore {
        @Override
        public Promise<Long> countSold(UUID eventId) {
            return Causes.cause("store unavailable").promise();
        }
    }

    private final FakeStore store = new FakeStore();
    private final SoldCount slice = SoldCount.soldCount(store);

    @Test
    void execute_twoSeatsSold_returnsCount() {
        var event = UUID.randomUUID();

        store.seed(event, 2L);
        slice.execute(new Request(event.toString())).await().onFailure(cause -> fail(cause.message())).onSuccess(r -> assertThat(r.sold()).isEqualTo(2));
    }

    @Test
    void execute_noSales_returnsZero() {
        slice.execute(new Request(UUID.randomUUID().toString())).await().onFailure(cause -> fail(cause.message())).onSuccess(r -> assertThat(r.sold()).isEqualTo(0));
    }

    @Test
    void execute_malformedEvent_returnsError() {
        slice.execute(new Request("not-a-uuid")).await().onSuccess(r -> fail("Expected validation failure")).onFailure(cause -> assertThat(cause.message()).contains("valid UUID"));
    }

    @Test
    void execute_storeFails_returnsStoreUnavailable() {
        var failing = SoldCount.soldCount(new FailingStore());

        failing.execute(new Request(UUID.randomUUID().toString())).await().onSuccess(r -> fail("Expected store failure")).onFailure(cause -> assertThat(cause).isInstanceOf(SoldCount.AvailabilityError.StoreUnavailable.class));
    }
}
