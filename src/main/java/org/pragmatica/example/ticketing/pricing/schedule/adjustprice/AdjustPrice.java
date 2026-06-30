package org.pragmatica.example.ticketing.pricing.schedule.adjustprice;

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
import org.pragmatica.example.ticketing.shared.event.PriceChanged;
import org.pragmatica.example.ticketing.shared.event.PriceChangedPublisher;

import java.util.UUID;


/// Use case: scale the current price for an (event, tier) by a demand percentage (110 = +10%).
/// Telescope leaf — system `ticketing` → subsystem `pricing` → workflow `schedule` → use case
/// `adjust-price`. Recovery class: design-out — the scaled price is a new appended row at a higher
/// version, never an overwrite. One use case, one `Request`/`Response` pair, one `execute` method.
@Slice
public interface AdjustPrice {
    @SuppressWarnings("JBCT-VO-01")
    record Request(String event, String tier, long percent) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(long version) {}

    /// Validated demand-adjust request; the percentage is parsed into a positive `Percent` value
    /// object so a non-positive scale (which could zero or invert the price) is rejected up front.
    record ValidAdjust(EventId event, PriceTier tier, Percent percent) {
        static Result<ValidAdjust> validAdjust(Request request) {
            return Result.all(EventId.eventId(request.event()),
                              PriceTier.priceTier(request.tier()),
                              Percent.percent(request.percent()))
                         .map(ValidAdjust::new);
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

        static AdjustError priceNotFound() {
            return new PriceNotFound();
        }

        static AdjustError storeUnavailable() {
            return new StoreUnavailable();
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
                                         write.tierName(),
                                         write.amountMinor(),
                                         write.currency()).mapError(_ -> AdjustError.storeUnavailable())
                                        .flatMap(version -> publishCommitted(write, version))
                                        .map(Response::new);
            }

            // JBCT pattern: Sequencer -- upsert the projection, publish the change, carry the version.
            private Promise<Long> publishCommitted(ValidWrite write, long version) {
                return store.upsertCurrent(write.scopeKey(),
                                           write.eventId(),
                                           write.tierName(),
                                           write.amountMinor(),
                                           write.currency(),
                                           version).mapError(_ -> AdjustError.storeUnavailable())
                                          .flatMap(_ -> publisher.publish(write.fact(version)))
                                          .map(_ -> version);
            }
        }

        return new adjustPrice(store, publisher);
    }
}
