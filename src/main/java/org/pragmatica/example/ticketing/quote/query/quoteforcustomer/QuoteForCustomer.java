package org.pragmatica.example.ticketing.quote.query.quoteforcustomer;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.PriceTier;
import org.pragmatica.example.ticketing.shared.Validation;


/// Use case: quote the customer-facing current price for an (event, tier) from the read projection.
/// Telescope leaf — system `ticketing` → subsystem `quote` → workflow `query` → use case
/// `quote-for-customer`. One use case, one `Request`/`Response` pair, one `execute` method.
@Slice
public interface QuoteForCustomer {
    record Request(String event, String tier) {}

    record Response(String event, String tier, long amountMinor, String currency, long version) {}

    /// Validated quote target. Each field is mapped to a cause declared in this slice's own package and
    /// the composite `Result.all` wraps them in is unwrapped again, because the generated router's error
    /// switch is built from this package's `Cause` types alone -- a shared cause, or the composite,
    /// falls through to HTTP 500 instead of the client-visible refusal it should be.
    record ValidQuery(EventId event, PriceTier tier) {
        static Result<ValidQuery> validQuery(Request request) {
            return Result.all(EventId.eventId(request.event()).mapError(QuoteError::invalidEvent),
                              PriceTier.priceTier(request.tier()).mapError(QuoteError::unacceptableTier))
                         .map(ValidQuery::new)
                         .mapError(Validation::firstFailure);
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
                return "Quote store is unavailable";
            }
        }

        /// Client-facing validation refusal (HTTP 400): a request field could not be parsed into its
        /// domain type. Declared in this slice's own hierarchy instead of letting the shared
        /// value-object cause through, because the slice processor builds the router's error switch
        /// from the `Cause` types in this package alone -- a shared cause arrives unmatched and falls
        /// through to HTTP 500. Data-carrying, so the response names the offending field and keeps the
        /// original reason.
        record InvalidRequest(String field, String detail) implements QuoteError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        /// Client-facing validation refusal (HTTP 422): a request field parsed cleanly but its value
        /// lies outside the field's admissible domain -- here, a well-formed token that names no member
        /// of the closed `PriceTier` set. It is deliberately not the 404 that [PriceNotFound] earns: an
        /// unknown tier is a malformed *question*, while `PriceNotFound` is a well-formed question with
        /// no answer, and conflating them would tell the caller a tier exists but is unpriced.
        record UnacceptableValue(String field, String detail) implements QuoteError {
            @Override
            public String message() {
                return "Unacceptable value for request field '" + field + "': " + detail;
            }
        }

        static QuoteError priceNotFound() {
            return new PriceNotFound();
        }

        static QuoteError storeUnavailable() {
            return new StoreUnavailable();
        }

        static QuoteError invalidEvent(Cause cause) {
            return new InvalidRequest("event", cause.message());
        }

        static QuoteError unacceptableTier(Cause cause) {
            return new UnacceptableValue("tier", cause.message());
        }
    }

    @QuoteCache
    Promise<Response> execute(Request request);

    static QuoteForCustomer quoteForCustomer(@PgSql QuoteViewStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record quoteForCustomer(QuoteViewStore store) implements QuoteForCustomer {
            @Override
            public Promise<Response> execute(Request request) {
                return ValidQuery.validQuery(request)
                                 .async()
                                 .flatMap(this::lookup);
            }

            private Promise<Response> lookup(ValidQuery query) {
                var event = query.event().value().value().toString();
                var tier = query.tier().name();

                return store.findByScope(event + ":" + tier)
                            .mapError(_ -> QuoteError.storeUnavailable())
                            .flatMap(found -> found.async(QuoteError.priceNotFound()))
                            .map(row -> new Response(event,
                                                     tier,
                                                     row.amountMinor(),
                                                     row.currency(),
                                                     row.version()));
            }
        }

        return new quoteForCustomer(store);
    }
}
