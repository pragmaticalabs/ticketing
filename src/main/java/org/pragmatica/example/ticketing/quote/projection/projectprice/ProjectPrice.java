package org.pragmatica.example.ticketing.quote.projection.projectprice;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.event.PriceChanged;
import org.pragmatica.example.ticketing.shared.event.PriceChangedSubscription;


/// Use case: keep the customer-facing `price_view` projection fresh from `PriceChanged` facts.
/// Telescope leaf — system `ticketing` → subsystem `quote` → workflow `projection` → use case
/// `project-price`. Event consumer (no HTTP route); a malformed fact or a transient store error is
/// recovered to `Unit` so the subscription never wedges (design-out: monotonic upsert converges).
@Slice
public interface ProjectPrice {
    @PriceChangedSubscription
    Promise<Unit> execute(PriceChanged event);

    static ProjectPrice projectPrice(@PgSql PriceProjectionStore store) {
        record projectPrice(PriceProjectionStore store) implements ProjectPrice {
            @Override
            public Promise<Unit> execute(PriceChanged event) {
                return EventId.eventId(event.eventId())
                              .async()
                              .flatMap(id -> project(id, event))
                              .recover(_ -> Unit.unit());
            }

            private Promise<Unit> project(EventId id, PriceChanged event) {
                return store.upsertPrice(event.eventId() + ":" + event.tier(),
                                         id.value().value(),
                                         event.tier(),
                                         event.amountMinor(),
                                         event.currency(),
                                         event.version());
            }
        }

        return new projectPrice(store);
    }
}
