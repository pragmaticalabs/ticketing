package org.pragmatica.example.ticketing.pricing.schedule.adjustprice;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.example.ticketing.pricing.PricingStore;
import org.pragmatica.example.ticketing.pricing.PricingStore.PriceRow;
import org.pragmatica.example.ticketing.pricing.schedule.adjustprice.AdjustPrice.Request;
import org.pragmatica.example.ticketing.shared.event.PriceChanged;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class AdjustPriceTest {
    private static final class FakeStore implements PricingStore {
        private final Map<String, PriceRow> rows = new HashMap<>();
        private final Map<String, Long> versions = new HashMap<>();

        // Allocate the next version for (event, tier) as max-existing + 1 (starting at 1), like the
        // real append-only price_events log, and return it.
        @Override
        public Promise<Long> appendPrice(UUID id,
                                         UUID eventId,
                                         String tier,
                                         long amountMinor,
                                         String currency) {
            return Promise.success(versions.merge(eventId + ":" + tier, 1L, (existing, increment) -> existing + 1L));
        }

        @Override
        public Promise<Unit> upsertCurrent(String scopeKey,
                                           UUID eventId,
                                           String tier,
                                           long amountMinor,
                                           String currency,
                                           long version) {
            rows.put(scopeKey, new PriceRow(amountMinor, currency, tier, version));

            return Promise.UNIT;
        }

        @Override
        public Promise<Option<PriceRow>> findCurrent(String scopeKey) {
            return Promise.success(Option.option(rows.get(scopeKey)));
        }
    }

    private static final class RecordingPublisher implements Publisher<PriceChanged> {
        private final List<PriceChanged> published = new ArrayList<>();

        @Override
        public Promise<Unit> publish(PriceChanged message) {
            published.add(message);

            return Promise.UNIT;
        }
    }

    private final FakeStore store = new FakeStore();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final AdjustPrice slice = AdjustPrice.adjustPrice(store, publisher);

    @Test
    void execute_priceSet_returnsScaledVersionAndPublishes() {
        var event = UUID.randomUUID().toString();

        store.upsertCurrent(event + ":STANDARD", UUID.fromString(event), "STANDARD", 5000, "USD", 1).await();
        slice.execute(new Request(event, "STANDARD", 110)).await().onFailure(cause -> fail(cause.message())).onSuccess(r -> assertThat(r.version()).isEqualTo(1L));
        assertThat(publisher.published).hasSize(1);
        assertThat(publisher.published.getFirst().amountMinor()).isEqualTo(5500);
        assertThat(publisher.published.getFirst().currency()).isEqualTo("USD");
    }

    @Test
    void execute_noPrice_returnsPriceNotFound() {
        slice.execute(new Request(UUID.randomUUID().toString(),
                                  "STANDARD",
                                  110)).await().onSuccess(r -> fail("Expected PriceNotFound")).onFailure(cause -> assertThat(cause.message()).contains("No price"));
    }

    @Test
    void execute_malformedEvent_returnsError() {
        slice.execute(new Request("not-a-uuid", "STANDARD", 110)).await().onSuccess(r -> fail("Expected validation failure")).onFailure(cause -> assertThat(cause.message()).contains("valid UUID"));
    }

    @Test
    void adjustPrice_negativePercent_returnsError() {
        slice.execute(new Request(UUID.randomUUID().toString(),
                                  "STANDARD",
                                  -10)).await().onSuccess(r -> fail("Expected validation failure")).onFailure(cause -> assertThat(cause.message()).contains("Percent must be positive"));
    }

    @Test
    void adjustPrice_zeroPercent_returnsError() {
        slice.execute(new Request(UUID.randomUUID().toString(),
                                  "STANDARD",
                                  0)).await().onSuccess(r -> fail("Expected validation failure")).onFailure(cause -> assertThat(cause.message()).contains("Percent must be positive"));
    }
}
