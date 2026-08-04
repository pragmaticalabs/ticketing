package org.pragmatica.example.ticketing.pricing.schedule.adjustprice;

import java.util.UUID;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.example.ticketing.pricing.PricingStore;
import org.pragmatica.example.ticketing.pricing.PricingStore.PriceRow;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.Money;
import org.pragmatica.example.ticketing.shared.Percent;
import org.pragmatica.example.ticketing.shared.PriceTier;
import org.pragmatica.example.ticketing.shared.Validation;
import org.pragmatica.example.ticketing.shared.event.PriceChanged;
import org.pragmatica.example.ticketing.shared.event.PriceChangedPublisher;


/// Use case: scale the current price for an (event, tier) by a demand percentage (110 = +10%).
/// Telescope leaf — system `ticketing` → subsystem `pricing` → workflow `schedule` → use case
/// `adjust-price`. Recovery class: design-out — the scaled price is a new appended row at a higher
/// version, never an overwrite. One use case, one `Request`/`Response` pair, one `execute` method.
@Slice
public interface AdjustPrice {
    record Request(String event, String tier, long percent) {}

    record Response(long version) {}

    /// Validated demand-adjust request; the percentage is parsed into a positive `Percent` value
    /// object so a non-positive scale (which could zero or invert the price) is rejected up front.
    ///
    /// Each field is mapped to a cause declared in this slice's own package and the composite
    /// `Result.all` wraps them in is unwrapped again, because the generated router's error switch is
    /// built from this package's `Cause` types alone -- a shared cause, or the composite, falls through
    /// to HTTP 500 rather than telling the caller which field it must fix.
    record ValidAdjust(EventId event, PriceTier tier, Percent percent) {
        static Result<ValidAdjust> validAdjust(Request request) {
            return Result.all(EventId.eventId(request.event()).mapError(AdjustError::invalidEvent),
                              PriceTier.priceTier(request.tier()).mapError(AdjustError::unacceptableTier),
                              Percent.percent(request.percent()).mapError(AdjustError::unacceptablePercent))
                         .map(ValidAdjust::new)
                         .mapError(Validation::firstFailure);
        }

        String scopeKey() {
            return event.value()
                        .value()
                        .toString() + ":" + tier.name();
        }
    }

    /// Write target derived after reading and scaling the current price; reused by the shared commit
    /// path so the demand scaling reuses `Money.scaledByPercent` rather than duplicating its rounding.
    record ValidWrite(EventId event, PriceTier tier, Money price) {
        static ValidWrite validWrite(EventId event, PriceTier tier, Money price) {
            return new ValidWrite(event, tier, price);
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

    sealed interface AdjustError extends Cause {
        record PriceNotFound() implements AdjustError {
            @Override
            public String message() {
                return "No price is available for this event and tier";
            }
        }

        record StoreUnavailable() implements AdjustError {
            @Override
            public String message() {
                return "Pricing store is unavailable";
            }
        }

        /// Client-facing validation refusal (HTTP 400): a request field could not be parsed into its
        /// domain type. Declared in this slice's own hierarchy instead of letting the shared
        /// value-object cause through, because the slice processor builds the router's error switch
        /// from the `Cause` types in this package alone -- a shared cause arrives unmatched and falls
        /// through to HTTP 500. Data-carrying, so the response names the offending field and keeps the
        /// original reason.
        record InvalidRequest(String field, String detail) implements AdjustError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        /// Client-facing validation refusal (HTTP 422): a request field parsed cleanly but its value
        /// lies outside the field's admissible domain -- a `percent` that is a perfectly well-formed
        /// JSON number yet not positive, or a well-formed token naming no member of the closed
        /// `PriceTier` set. Nothing about the request syntax is wrong, so 422 is the honest status;
        /// [InvalidRequest] keeps 400 for text that could not be parsed at all.
        record UnacceptableValue(String field, String detail) implements AdjustError {
            @Override
            public String message() {
                return "Unacceptable value for request field '" + field + "': " + detail;
            }
        }

        static AdjustError priceNotFound() {
            return new PriceNotFound();
        }

        static AdjustError storeUnavailable() {
            return new StoreUnavailable();
        }

        static AdjustError invalidEvent(Cause cause) {
            return new InvalidRequest("event", cause.message());
        }

        static AdjustError unacceptableTier(Cause cause) {
            return new UnacceptableValue("tier", cause.message());
        }

        static AdjustError unacceptablePercent(Cause cause) {
            return new UnacceptableValue("percent", cause.message());
        }
    }

    Promise<Response> execute(Request request);

    static AdjustPrice adjustPrice(@PgSql PricingStore store,
                                   @PriceChangedPublisher Publisher<PriceChanged> publisher) {
        @SuppressWarnings("JBCT-SEQ-01")
        record adjustPrice(PricingStore store, Publisher<PriceChanged> publisher) implements AdjustPrice {
            // JBCT pattern: Sequencer -- validate -> read current -> scale -> commit.
            @Override
            public Promise<Response> execute(Request request) {
                return ValidAdjust.validAdjust(request)
                                  .async()
                                  .flatMap(this::adjust);
            }

            private Promise<Response> adjust(ValidAdjust valid) {
                return readCurrentPrice(valid.scopeKey()).map(price -> price.scaledByPercent(valid.percent()))
                                       .flatMap(scaled -> commit(ValidWrite.validWrite(valid.event(),
                                                                                       valid.tier(),
                                                                                       scaled)));
            }

            private Promise<Money> readCurrentPrice(String scopeKey) {
                return store.findCurrent(scopeKey)
                            .mapError(_ -> AdjustError.storeUnavailable())
                            .flatMap(found -> found.async(AdjustError.priceNotFound()))
                            .flatMap(this::toMoney);
            }

            // Reconstruct the value object from the projection row using Money's canonical from-minor
            // constructor and a revalidated Currency, so the demand scaling reuses Money's rounding.
            private Promise<Money> toMoney(PriceRow row) {
                return Money.Currency.currency(row.currency())
                                     .map(currency -> new Money(row.amountMinor(),
                                                                currency))
                                     .async();
            }

            private Promise<Response> commit(ValidWrite write) {
                return store.appendPrice(UUID.randomUUID(),
                                         write.eventId(),
                                         write.tier(),
                                         write.amountMinor(),
                                         write.currency())
                            .mapError(_ -> AdjustError.storeUnavailable())
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
                            .mapError(_ -> AdjustError.storeUnavailable())
                            .flatMap(_ -> publisher.publish(write.fact(version)))
                            .map(_ -> version);
            }
        }

        return new adjustPrice(store, publisher);
    }
}
