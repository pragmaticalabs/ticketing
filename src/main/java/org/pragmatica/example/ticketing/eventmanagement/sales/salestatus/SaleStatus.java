package org.pragmatica.example.ticketing.eventmanagement.sales.salestatus;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.eventmanagement.EventStatus;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.eventmanagement.EventStore.EventRow;
import org.pragmatica.example.ticketing.shared.EventId;


/// Use case: read whether an event is currently selling (direct read, also called by booking).
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `sales` -> use case
/// `sale-status`. One use case, one `Request`/`Response` pair, one `execute` method.
///
/// "Selling" is decided by comparing the parsed [EventStatus] against `ON_SALE`, not by comparing the
/// raw column against a string literal -- a typo or a renamed status is now a row-decode failure rather
/// than a silently `false` answer.
///
/// `events.on_sale_at` is nullable. The store hands it over as an `Option`, and this slice is where the
/// wire encoding is chosen: an absent on-sale time is rendered as the empty string, because
/// `Response.onSaleAt` is a plain `String` consumed by booking's `buy-ticket`. That is a deliberate,
/// documented encoding at the boundary -- not the Java `null` that previously reached the response.
@Slice
public interface SaleStatus {
    record Request(String event) {}

    record Response(String event, boolean onSale, String onSaleAt) {}

    Promise<Response> execute(Request request);

    sealed interface SaleStatusError extends Cause {
        /// Fixed-message reads that found nothing. Every constant here is an HTTP 404 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 404 must not be added to it, and one
        /// `*EntityMissing*` pattern maps the whole enum.
        enum EntityMissing implements SaleStatusError {
            EVENT("Event not found");
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
        enum ServiceUnavailable implements SaleStatusError {
            EVENT_MANAGEMENT_STORE("Event management store is unavailable");
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
        record InvalidRequest(String field, String detail) implements SaleStatusError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        static SaleStatusError eventNotFound() {
            return EntityMissing.EVENT;
        }

        static SaleStatusError storeUnavailable() {
            return ServiceUnavailable.EVENT_MANAGEMENT_STORE;
        }

        static SaleStatusError invalidEvent(Cause cause) {
            return new InvalidRequest("event", cause.message());
        }
    }

    static SaleStatus saleStatus(@PgSql EventStore store) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
        record saleStatus(EventStore store) implements SaleStatus {
            // JBCT pattern: Sequencer -- validate -> read current state.
            @Override
            public Promise<Response> execute(Request request) {
                return EventId.eventId(request.event())
                              .mapError(SaleStatusError::invalidEvent)
                              .async()
                              .flatMap(this::loadSaleStatus);
            }

            private Promise<Response> loadSaleStatus(EventId eventId) {
                var eventString = eventId.value().value().toString();

                return store.findEvent(eventId.value().value())
                            .mapError(_ -> SaleStatusError.storeUnavailable())
                            .flatMap(found -> found.async(SaleStatusError.eventNotFound()))
                            .map(row -> response(eventString, row));
            }

            private Response response(String event, EventRow row) {
                return new Response(event,
                                    row.status() == EventStatus.ON_SALE,
                                    row.onSaleAt().or(""));
            }
        }

        return new saleStatus(store);
    }
}
