package org.pragmatica.example.ticketing.pricing.quoting.quoteprice;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.example.ticketing.pricing.PricingStore;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.PriceTier;


/// Use case: quote the authoritative current price for an (event, tier).
/// Telescope leaf — system `ticketing` → subsystem `pricing` → workflow `quoting` → use case
/// `quote-price`. One use case, one `Request`/`Response` pair, one `execute` method.
@Slice
public interface QuotePrice {
    @SuppressWarnings("JBCT-VO-01")
    record Request(String event, String tier) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String event, String tier, long amountMinor, String currency, long version) {}

    record ValidQuery(EventId event, PriceTier tier) {
        static Result<ValidQuery> validQuery(Request request) {
            return Result.all(EventId.eventId(request.event()),
                              PriceTier.priceTier(request.tier()))
                         .map(ValidQuery::new);
        }
    }

    sealed interface QuoteError extends Cause {
        record PriceNotFound() implements QuoteError {
            @Override
            public String message() {
                return "No price is available for this event and tier";
            }
        }

        record StoreUnavailable() implements QuoteError {
            @Override
            public String message() {
                return "Pricing store is unavailable";
            }
        }

        static QuoteError priceNotFound() {
            return new PriceNotFound();
        }

        static QuoteError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static QuotePrice quotePrice(@PgSql PricingStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record quotePrice(PricingStore store) implements QuotePrice {
            @Override
            public Promise<Response> execute(Request request) {
                return ValidQuery.validQuery(request)
                                 .async()
                                 .flatMap(this::lookup);
            }

            private Promise<Response> lookup(ValidQuery query) {
                var eventId = query.event().value().value().toString();
                var tier = query.tier().name();

                return store.findCurrent(eventId + ":" + tier)
                            .mapError(_ -> QuoteError.storeUnavailable())
                            .flatMap(found -> found.async(QuoteError.priceNotFound()))
                            .map(row -> new Response(eventId,
                                                     tier,
                                                     row.amountMinor(),
                                                     row.currency(),
                                                     row.version()));
            }
        }

        return new quotePrice(store);
    }
}
