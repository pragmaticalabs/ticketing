package org.pragmatica.example.ticketing.pricing.quoting.quoteprice;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.pricing.PricingStore;
import org.pragmatica.example.ticketing.pricing.PricingStore.PriceRow;
import org.pragmatica.example.ticketing.pricing.quoting.quoteprice.QuotePrice.Request;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class QuotePriceTest {
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

    private final FakeStore store = new FakeStore();
    private final QuotePrice slice = QuotePrice.quotePrice(store);

    @Test
    void execute_priceSet_returnsQuote() {
        var event = UUID.randomUUID().toString();

        store.upsertCurrent(event + ":STANDARD", UUID.fromString(event), "STANDARD", 4950, "USD", 1).await();
        slice.execute(new Request(event, "STANDARD")).await().onFailure(cause -> fail(cause.message())).onSuccess(r -> {
            assertThat(r.amountMinor()).isEqualTo(4950);
            assertThat(r.currency()).isEqualTo("USD");
        });
    }

    @Test
    void execute_noPrice_returnsPriceNotFound() {
        slice.execute(new Request(UUID.randomUUID().toString(),
                                  "STANDARD")).await().onSuccess(r -> fail("Expected PriceNotFound")).onFailure(cause -> assertThat(cause.message()).contains("No price"));
    }

    @Test
    void execute_malformedEvent_returnsError() {
        slice.execute(new Request("not-a-uuid", "STANDARD")).await().onSuccess(r -> fail("Expected validation failure")).onFailure(cause -> assertThat(cause.message()).contains("valid UUID"));
    }

    @Test
    void execute_storeFails_returnsStoreUnavailable() {
        var failing = QuotePrice.quotePrice(new FailingStore());

        failing.execute(new Request(UUID.randomUUID().toString(),
                                    "STANDARD")).await().onSuccess(r -> fail("Expected store failure")).onFailure(cause -> assertThat(cause).isInstanceOf(QuotePrice.QuoteError.StoreUnavailable.class));
    }
}
