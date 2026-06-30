package org.pragmatica.example.ticketing.pricing.schedule.setprice;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.pricing.PricingStore;
import org.pragmatica.example.ticketing.pricing.PricingStore.PriceRow;
import org.pragmatica.example.ticketing.pricing.schedule.setprice.SetPrice.Request;
import org.pragmatica.example.ticketing.shared.event.PriceChanged;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class SetPriceTest {
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

    // Store whose every operation fails, to simulate the pricing store being unavailable.
    private static final class FailingStore implements PricingStore {
        @Override
        public Promise<Long> appendPrice(UUID id,
                                         UUID eventId,
                                         String tier,
                                         long amountMinor,
                                         String currency) {
            return Causes.cause("pricing store unavailable").promise();
        }

        @Override
        public Promise<Unit> upsertCurrent(String scopeKey,
                                           UUID eventId,
                                           String tier,
                                           long amountMinor,
                                           String currency,
                                           long version) {
            return Causes.cause("pricing store unavailable").promise();
        }

        @Override
        public Promise<Option<PriceRow>> findCurrent(String scopeKey) {
            return Causes.cause("pricing store unavailable").promise();
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
    private final SetPrice slice = SetPrice.setPrice(store, publisher);

    @Test
    void execute_validRequest_returnsVersionAndPublishes() {
        var event = UUID.randomUUID().toString();

        slice.execute(new Request(event, "STANDARD", "49.50", "USD")).await().onFailure(cause -> fail(cause.message())).onSuccess(r -> assertThat(r.version()).isEqualTo(1L));
        assertThat(publisher.published).hasSize(1);
        assertThat(publisher.published.getFirst().amountMinor()).isEqualTo(4950);
        assertThat(publisher.published.getFirst().currency()).isEqualTo("USD");
    }

    @Test
    void execute_malformedEvent_returnsError() {
        slice.execute(new Request("not-a-uuid", "STANDARD", "49.50", "USD")).await().onSuccess(r -> fail("Expected validation failure")).onFailure(cause -> assertThat(cause.message()).contains("valid UUID"));
    }

    @Test
    void execute_malformedAmount_returnsError() {
        slice.execute(new Request(UUID.randomUUID().toString(),
                                  "STANDARD",
                                  "not-a-number",
                                  "USD")).await().onSuccess(r -> fail("Expected validation failure")).onFailure(cause -> assertThat(cause.message()).contains("malformed"));
    }

    @Test
    void setPrice_unknownCurrency_returnsError() {
        slice.execute(new Request(UUID.randomUUID().toString(),
                                  "STANDARD",
                                  "49.50",
                                  "XYZ")).await().onSuccess(r -> fail("Expected validation failure")).onFailure(cause -> assertThat(cause.message()).contains("Unknown currency"));
    }

    @Test
    void setPrice_unknownTier_returnsError() {
        slice.execute(new Request(UUID.randomUUID().toString(),
                                  "NOSUCHTIER",
                                  "49.50",
                                  "USD")).await().onSuccess(r -> fail("Expected validation failure")).onFailure(cause -> assertThat(cause.message()).contains("Unknown price tier"));
    }

    @Test
    void setPrice_storeFails_returnsStoreUnavailable() {
        var failing = SetPrice.setPrice(new FailingStore(), publisher);

        failing.execute(new Request(UUID.randomUUID().toString(),
                                    "STANDARD",
                                    "49.50",
                                    "USD")).await().onSuccess(r -> fail("Expected store failure")).onFailure(cause -> assertThat(cause).isInstanceOf(SetPrice.PricingError.StoreUnavailable.class));
    }
}
