package org.pragmatica.example.ticketing.quote.projection.projectprice;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.shared.event.PriceChanged;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class ProjectPriceTest {
    // Test-only stored representation of a projection row. The write-side store exposes no reader, so
    // the fake keeps its own rows and offers `rowOf` for inspection.
    private record PriceRow(long amountMinor, String currency, String tier, long version) {}

    private static final class FakeStore implements PriceProjectionStore {
        private final Map<String, PriceRow> rows = new HashMap<>();

        // Monotonic upsert, mirroring `WHERE price_view.version < EXCLUDED.version`: a stale (lower
        // version) fact is a no-op, so out-of-order delivery converges.
        @Override
        public Promise<Unit> upsertPrice(String scopeKey,
                                         UUID eventId,
                                         String tier,
                                         long amountMinor,
                                         String currency,
                                         long version) {
            rows.merge(scopeKey, new PriceRow(amountMinor, currency, tier, version), FakeStore::monotonic);

            return Promise.UNIT;
        }

        // Test-only inspection of the projected state.
        Option<PriceRow> rowOf(String scopeKey) {
            return Option.option(rows.get(scopeKey));
        }

        private static PriceRow monotonic(PriceRow existing, PriceRow incoming) {
            return incoming.version() > existing.version() ? incoming : existing;
        }
    }

    // Store whose upsert fails, to verify the subscription recovers to Unit instead of wedging.
    private static final class FailingStore implements PriceProjectionStore {
        @Override
        public Promise<Unit> upsertPrice(String scopeKey,
                                         UUID eventId,
                                         String tier,
                                         long amountMinor,
                                         String currency,
                                         long version) {
            return Causes.cause("quote store unavailable").promise();
        }
    }

    private final FakeStore store = new FakeStore();
    private final ProjectPrice slice = ProjectPrice.projectPrice(store);

    @Test
    void execute_priceChanged_storesProjection() {
        var event = UUID.randomUUID().toString();

        slice.execute(new PriceChanged(event, "seat-1", "STANDARD", 4950, "USD", 1)).await().onFailure(cause -> fail(cause.message()));
        store.rowOf(event + ":STANDARD").onEmpty(() -> fail("Expected stored projection"))
                                        .onPresent(row -> {
                                                       assertThat(row.amountMinor()).isEqualTo(4950);
                                                       assertThat(row.currency()).isEqualTo("USD");
                                                       assertThat(row.version()).isEqualTo(1);
                                                   });
    }

    @Test
    void execute_storeFails_recoversToUnit() {
        var failing = ProjectPrice.projectPrice(new FailingStore());

        failing.execute(new PriceChanged(UUID.randomUUID().toString(),
                                         "seat-1",
                                         "STANDARD",
                                         4950,
                                         "USD",
                                         1)).await().onFailure(cause -> fail("Expected recovery to Unit"));
    }

    @Test
    void execute_malformedFact_recoversToUnit() {
        slice.execute(new PriceChanged("not-a-uuid", "seat-1", "STANDARD", 4950, "USD", 1)).await().onFailure(cause -> fail("Expected recovery to Unit"));
    }

    @Test
    void execute_staleVersion_leavesRowUnchanged() {
        var event = UUID.randomUUID().toString();

        slice.execute(new PriceChanged(event, "seat-1", "STANDARD", 5500, "USD", 2)).await().onFailure(cause -> fail(cause.message()));
        slice.execute(new PriceChanged(event, "seat-1", "STANDARD", 5000, "USD", 1)).await().onFailure(cause -> fail(cause.message()));
        store.rowOf(event + ":STANDARD").onEmpty(() -> fail("Expected stored projection"))
                                        .onPresent(row -> {
                                                       assertThat(row.amountMinor()).isEqualTo(5500);
                                                       assertThat(row.version()).isEqualTo(2);
                                                   });
    }
}
