package org.pragmatica.example.ticketing.eventmanagement.sales.salestatus;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.shared.EventId;


/// Use case: read whether an event is currently selling (direct read, also called by booking).
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `sales` -> use case
/// `sale-status`. One use case, one `Request`/`Response` pair, one `execute` method.
@Slice
public interface SaleStatus {
    @SuppressWarnings("JBCT-VO-01")
    record Request(String event) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String event, boolean onSale, String onSaleAt) {}

    sealed interface SaleStatusError extends Cause {
        record EventNotFound() implements SaleStatusError {
            @Override
            public String message() {
                return "Event not found";
            }
        }

        record StoreUnavailable() implements SaleStatusError {
            @Override
            public String message() {
                return "Event management store is unavailable";
            }
        }

        static SaleStatusError eventNotFound() {
            return new EventNotFound();
        }

        static SaleStatusError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static SaleStatus saleStatus(@PgSql EventStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record saleStatus(EventStore store) implements SaleStatus {
            // JBCT pattern: Sequencer -- validate -> read current state.
            @Override
            public Promise<Response> execute(Request request) {
                return EventId.eventId(request.event())
                              .async()
                              .flatMap(this::loadSaleStatus);
            }

            private Promise<Response> loadSaleStatus(EventId eventId) {
                var eventString = eventId.value().value().toString();

                return store.findEvent(eventId.value().value())
                            .mapError(_ -> SaleStatusError.storeUnavailable())
                            .flatMap(found -> found.async(SaleStatusError.eventNotFound()))
                            .map(row -> new Response(eventString,
                                                     row.status().equals("on_sale"),
                                                     row.onSaleAt()));
            }
        }

        return new saleStatus(store);
    }
}
