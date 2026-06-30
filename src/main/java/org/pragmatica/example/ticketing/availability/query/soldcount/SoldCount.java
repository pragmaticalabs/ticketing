package org.pragmatica.example.ticketing.availability.query.soldcount;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.shared.EventId;


/// Use case: count the sold seats of an event from the availability projection.
/// Telescope leaf — system `ticketing` → subsystem `availability` → workflow `query` → use case
/// `sold-count`. One use case, one `Request`/`Response` pair, one `execute` method.
@Slice
public interface SoldCount {
    @SuppressWarnings("JBCT-VO-01")
    record Request(String event) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String event, long sold) {}

    sealed interface AvailabilityError extends Cause {
        record StoreUnavailable() implements AvailabilityError {
            @Override
            public String message() {
                return "Availability store is unavailable";
            }
        }

        static AvailabilityError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static SoldCount soldCount(@PgSql SoldCountStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record soldCount(SoldCountStore store) implements SoldCount {
            // JBCT pattern: Sequencer -- validate -> count -> respond.
            @Override
            public Promise<Response> execute(Request request) {
                return EventId.eventId(request.event())
                              .async()
                              .flatMap(this::countFor);
            }

            private Promise<Response> countFor(EventId eventId) {
                var event = eventId.value().value().toString();

                return store.countSold(eventId.value().value())
                            .mapError(_ -> AvailabilityError.storeUnavailable())
                            .map(sold -> new Response(event, sold));
            }
        }

        return new soldCount(store);
    }
}
