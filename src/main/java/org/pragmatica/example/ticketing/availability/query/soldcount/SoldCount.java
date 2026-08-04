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
    record Request(String event) {}

    record Response(String event, long sold) {}

    @SoldCountCache
    Promise<Response> execute(Request request);

    sealed interface AvailabilityError extends Cause {
        /// Fixed-message failures of a dependency this slice calls. Every constant here is an HTTP 503 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 503 must not be added to it, and one
        /// `*ServiceUnavailable*` pattern maps the whole enum.
        enum ServiceUnavailable implements AvailabilityError {
            AVAILABILITY_STORE("Availability store is unavailable");
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
        record InvalidRequest(String field, String detail) implements AvailabilityError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        static AvailabilityError storeUnavailable() {
            return ServiceUnavailable.AVAILABILITY_STORE;
        }

        static AvailabilityError invalidEvent(Cause cause) {
            return new InvalidRequest("event", cause.message());
        }
    }

    static SoldCount soldCount(@PgSql SoldCountStore store) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
        record soldCount(SoldCountStore store) implements SoldCount {
            // JBCT pattern: Sequencer -- validate -> count -> respond.
            @Override
            public Promise<Response> execute(Request request) {
                return EventId.eventId(request.event())
                              .mapError(AvailabilityError::invalidEvent)
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
