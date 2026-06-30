package org.pragmatica.example.ticketing.pricing.schedule.setprice;

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
import org.pragmatica.example.ticketing.shared.event.PriceChanged;
import org.pragmatica.example.ticketing.shared.event.PriceChangedPublisher;

import java.util.UUID;


/// Use case: set an absolute price for an (event, tier).
/// Telescope leaf — system `ticketing` → subsystem `pricing` → workflow `schedule` → use case
/// `set-price`. Recovery class: design-out — a correction is a new appended row at a higher
/// version, never an overwrite. One use case, one `Request`/`Response` pair, one `execute` method.
@Slice
public interface SetPrice {
    @SuppressWarnings("JBCT-VO-01")
    record Request(String event, String tier, String amount, String currency) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(long version) {}

    /// Validated write target: the raw request fields are parsed into value objects; all failures
    /// surface together via `Result.all`.
    record ValidWrite(EventId event, PriceTier tier, Money price) {
        static Result<ValidWrite> validWrite(Request request) {
            return Result.all(EventId.eventId(request.event()),
                              PriceTier.priceTier(request.tier()),
                              Money.money(request.amount(),
                                          request.currency()))
                         .map(ValidWrite::new);
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

    sealed interface PricingError extends Cause {
        record StoreUnavailable() implements PricingError {
            @Override
            public String message() {
                return "Pricing store is unavailable";
            }
        }

        static PricingError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static SetPrice setPrice(@PgSql PricingStore store, @PriceChangedPublisher Publisher<PriceChanged> publisher) {
        @SuppressWarnings("JBCT-SEQ-01")
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
                                         write.tierName(),
                                         write.amountMinor(),
                                         write.currency()).mapError(_ -> PricingError.storeUnavailable())
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
                                           version).mapError(_ -> PricingError.storeUnavailable())
                                          .flatMap(_ -> publisher.publish(write.fact(version)))
                                          .map(_ -> version);
            }
        }

        return new setPrice(store, publisher);
    }
}
