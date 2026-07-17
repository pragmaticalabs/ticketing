package org.pragmatica.example.ticketing.quote.projection.projectprice;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.PriceTier;
import org.pragmatica.example.ticketing.shared.event.PriceChanged;
import org.pragmatica.example.ticketing.shared.event.PriceChangedSubscription;


/// Use case: keep the customer-facing `price_view` projection fresh from `PriceChanged` facts.
/// Telescope leaf — system `ticketing` → subsystem `quote` → workflow `projection` → use case
/// `project-price`. Event consumer (no HTTP route); a malformed fact or a transient store error is
/// recovered to `Unit` so the subscription never wedges (design-out: monotonic upsert converges).
@Slice
public interface ProjectPrice {
    /// Parsed identity of a `PriceChanged` fact: the event and price tier as value objects, so a
    /// malformed fact fails the decode instead of writing a corrupt projection scope key.
    record ValidPriceRef(EventId event, PriceTier tier) {
        static Result<ValidPriceRef> validPriceRef(PriceChanged event) {
            return Result.all(EventId.eventId(event.eventId()),
                              PriceTier.priceTier(event.tier()))
                         .map(ValidPriceRef::new);
        }

        String scopeKey() {
            return event.value()
                        .value()
                        .toString() + ":" + tier.name();
        }
    }

    @PriceChangedSubscription
    Promise<Unit> execute(PriceChanged event);

    static ProjectPrice projectPrice(@PgSql PriceProjectionStore store) {
        record projectPrice(PriceProjectionStore store) implements ProjectPrice {
            @Override
            public Promise<Unit> execute(PriceChanged event) {
                return ValidPriceRef.validPriceRef(event)
                                    .async()
                                    .flatMap(ref -> project(ref, event))
                                    .recover(_ -> Unit.unit());
            }

            private Promise<Unit> project(ValidPriceRef ref, PriceChanged event) {
                return store.upsertPrice(ref.scopeKey(),
                                         ref.event().value().value(),
                                         ref.tier(),
                                         event.amountMinor(),
                                         event.currency(),
                                         event.version());
            }
        }

        return new projectPrice(store);
    }
}
