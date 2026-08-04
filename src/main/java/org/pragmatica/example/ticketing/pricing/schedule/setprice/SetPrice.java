package org.pragmatica.example.ticketing.pricing.schedule.setprice;

import java.util.UUID;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.example.ticketing.pricing.PricingStore;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.Money;
import org.pragmatica.example.ticketing.shared.PriceTier;
import org.pragmatica.example.ticketing.shared.Validation;
import org.pragmatica.example.ticketing.shared.event.PriceChanged;
import org.pragmatica.example.ticketing.shared.event.PriceChangedPublisher;


/// Use case: set an absolute price for an (event, tier).
/// Telescope leaf — system `ticketing` → subsystem `pricing` → workflow `schedule` → use case
/// `set-price`. Recovery class: design-out — a correction is a new appended row at a higher
/// version, never an overwrite. One use case, one `Request`/`Response` pair, one `execute` method.
@Slice
public interface SetPrice {
    record Request(String event, String tier, String amount, String currency) {}

    record Response(long version) {}

    /// Validated write target: the raw request fields are parsed into value objects; all failures
    /// surface together via `Result.all`.
    ///
    /// Each field is mapped to a cause declared in this slice's own package and the composite
    /// `Result.all` wraps them in is unwrapped again, because the generated router's error switch is
    /// built from this package's `Cause` types alone -- a shared cause, or the composite, falls through
    /// to HTTP 500 rather than telling the caller which field it must fix.
    record ValidWrite(EventId event, PriceTier tier, Money price) {
        static Result<ValidWrite> validWrite(Request request) {
            return Result.all(EventId.eventId(request.event()).mapError(PricingError::invalidEvent),
                              PriceTier.priceTier(request.tier()).mapError(PricingError::unacceptableTier),
                              Money.money(request.amount(),
                                          request.currency()).mapError(PricingError::invalidPrice))
                         .map(ValidWrite::new)
                         .mapError(Validation::firstFailure);
        }

        UUID eventId() {
            return event.value()
                        .value();
        }

        String tierName() {
            return tier.name();
        }

        long amountMinor() {
            return price.amountMinor();
        }

        String currency() {
            return price.currency()
                        .name();
        }

        String scopeKey() {
            return eventId().toString() + ":" + tierName();
        }

        // Tier-level pricing, so the fact carries an empty seat id.
        PriceChanged fact(long version) {
            return new PriceChanged(eventId().toString(), "", tierName(), amountMinor(), currency(), version);
        }
    }

    Promise<Response> execute(Request request);

    sealed interface PricingError extends Cause {
        /// Fixed-message failures of a dependency this slice calls. Every constant here is an HTTP 503 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 503 must not be added to it, and one
        /// `*ServiceUnavailable*` pattern maps the whole enum.
        enum ServiceUnavailable implements PricingError {
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
        /// domain type -- a malformed event UUID, or an `amount` that is not a number at all. Declared
        /// in this slice's own hierarchy instead of letting the shared value-object cause through,
        /// because the slice processor builds the router's error switch from the `Cause` types in this
        /// package alone -- a shared cause arrives unmatched and falls through to HTTP 500.
        record InvalidRequest(String field, String detail) implements PricingError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        /// Client-facing validation refusal (HTTP 422): a request field parsed cleanly but its value
        /// lies outside the field's admissible domain -- a negative `amount`, or a well-formed token
        /// naming no member of the closed `PriceTier`/`Currency` sets. The request syntax is faultless
        /// in every one of those cases, so 422 is the honest status; [InvalidRequest] keeps 400 for text
        /// that could not be parsed at all.
        record UnacceptableValue(String field, String detail) implements PricingError {
            @Override
            public String message() {
                return "Unacceptable value for request field '" + field + "': " + detail;
            }
        }

        static PricingError storeUnavailable() {
            return ServiceUnavailable.PRICING_STORE;
        }

        static PricingError invalidEvent(Cause cause) {
            return new InvalidRequest("event", cause.message());
        }

        static PricingError unacceptableTier(Cause cause) {
            return new UnacceptableValue("tier", cause.message());
        }

        /// `Money` validates amount and currency with its own `Result.all`, so the cause arrives
        /// composite; it is unwrapped before the field that was rejected can be named.
        static PricingError invalidPrice(Cause cause) {
            return priceFailure(Validation.firstFailure(cause));
        }

        /// An unparseable amount is a syntax failure (400); a negative amount or an unknown currency
        /// parses cleanly and is refused on meaning alone (422).
        private static PricingError priceFailure(Cause cause) {
            return switch (cause) {
                case Money.Error.MalformedAmount _ -> new InvalidRequest("amount", cause.message());
                case Money.Error.NegativeAmount _ -> new UnacceptableValue("amount", cause.message());
                case Money.Error.UnknownCurrency _ -> new UnacceptableValue("currency", cause.message());
                default -> new InvalidRequest("price", cause.message());
            };
        }
    }

    static SetPrice setPrice(@PgSql PricingStore store, @PriceChangedPublisher Publisher<PriceChanged> publisher) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
        record setPrice(PricingStore store, Publisher<PriceChanged> publisher) implements SetPrice {
            // JBCT pattern: Sequencer -- validate -> commit (append@version -> upsert -> publish).
            @Override
            public Promise<Response> execute(Request request) {
                return ValidWrite.validWrite(request)
                                 .async()
                                 .flatMap(this::commit);
            }

            private Promise<Response> commit(ValidWrite write) {
                return store.appendPrice(UUID.randomUUID(),
                                         write.eventId(),
                                         write.tier(),
                                         write.amountMinor(),
                                         write.currency())
                            .mapError(_ -> PricingError.storeUnavailable())
                            .flatMap(version -> publishCommitted(write, version))
                            .map(Response::new);
            }

            // JBCT pattern: Sequencer -- upsert the projection, publish the change, carry the version.
            private Promise<Long> publishCommitted(ValidWrite write, long version) {
                return store.upsertCurrent(write.scopeKey(),
                                           write.eventId(),
                                           write.tier(),
                                           write.amountMinor(),
                                           write.currency(),
                                           version)
                            .mapError(_ -> PricingError.storeUnavailable())
                            .flatMap(_ -> publisher.publish(write.fact(version)))
                            .map(_ -> version);
            }
        }

        return new setPrice(store, publisher);
    }
}
