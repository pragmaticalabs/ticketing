package org.pragmatica.example.ticketing.quote.query.quoteforcustomer;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.quote.query.quoteforcustomer.QuoteViewStore.PriceRow;
import org.pragmatica.example.ticketing.quote.query.quoteforcustomer.QuoteForCustomer.Request;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class QuoteForCustomerTest {
    private static final class FakeStore implements QuoteViewStore {
        private final Map<String, PriceRow> rows = new HashMap<>();

        // Test-only seeding: the read-side store has no writer, so projection rows are placed directly.
        void seed(String scopeKey, PriceRow row) {
            rows.put(scopeKey, row);
        }

        @Override
        public Promise<Option<PriceRow>> findByScope(String scopeKey) {
            return Promise.success(Option.option(rows.get(scopeKey)));
        }
    }

    // Store whose every operation fails, to simulate the quote store being unavailable.
    private static final class FailingStore implements QuoteViewStore {
        @Override
        public Promise<Option<PriceRow>> findByScope(String scopeKey) {
            return Causes.cause("quote store unavailable").promise();
        }
    }

    private final FakeStore store = new FakeStore();
    private final QuoteForCustomer slice = QuoteForCustomer.quoteForCustomer(store);

    @Test
    void execute_priceSet_returnsQuote() {
        var event = UUID.randomUUID().toString();

        store.seed(event + ":STANDARD", new PriceRow(4950, "USD", "STANDARD", 1));
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
        var failing = QuoteForCustomer.quoteForCustomer(new FailingStore());

        failing.execute(new Request(UUID.randomUUID().toString(),
                                    "STANDARD")).await().onSuccess(r -> fail("Expected store failure")).onFailure(cause -> assertThat(cause).isInstanceOf(QuoteForCustomer.QuoteError.StoreUnavailable.class));
    }
}
