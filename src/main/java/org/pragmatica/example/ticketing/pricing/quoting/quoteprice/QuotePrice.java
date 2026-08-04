package org.pragmatica.example.ticketing.pricing.quoting.quoteprice;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.example.ticketing.pricing.PricingStore;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.PriceTier;
import org.pragmatica.example.ticketing.shared.Validation;


/// Use case: quote the authoritative current price for an (event, tier).
/// Telescope leaf — system `ticketing` → subsystem `pricing` → workflow `quoting` → use case
/// `quote-price`. One use case, one `Request`/`Response` pair, one `execute` method.
@Slice
public interface QuotePrice {
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

    Promise<Response> execute(Request request);

    sealed interface QuoteError extends Cause {
        /// Fixed-message reads that found nothing. Every constant here is an HTTP 404 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 404 must not be added to it, and one
        /// `*EntityMissing*` pattern maps the whole enum.
        enum EntityMissing implements QuoteError {
            PRICE("No price is available for this event and tier");
            private final String message;
            EntityMissing(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        /// Fixed-message failures of a dependency this slice calls. Every constant here is an HTTP 503 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 503 must not be added to it, and one
        /// `*ServiceUnavailable*` pattern maps the whole enum.
        enum ServiceUnavailable implements QuoteError {
            PRICING_STORE("Pricing store is unavailable");
            private final String message;
            ServiceUnavailable(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
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
        /// of the closed `PriceTier` set. It is deliberately not the 404 that [EntityMissing#PRICE] earns: an
        /// unknown tier is a malformed *question*, while [EntityMissing#PRICE] is a well-formed question with
        /// no answer, and conflating them would tell the caller a tier exists but is unpriced.
        record UnacceptableValue(String field, String detail) implements QuoteError {
            @Override
            public String message() {
                return "Unacceptable value for request field '" + field + "': " + detail;
            }
        }

        static QuoteError priceNotFound() {
            return EntityMissing.PRICE;
        }

        static QuoteError storeUnavailable() {
            return ServiceUnavailable.PRICING_STORE;
        }

        static QuoteError invalidEvent(Cause cause) {
            return new InvalidRequest("event", cause.message());
        }

        static QuoteError unacceptableTier(Cause cause) {
            return new UnacceptableValue("tier", cause.message());
        }
    }

    static QuotePrice quotePrice(@PgSql PricingStore store) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
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
