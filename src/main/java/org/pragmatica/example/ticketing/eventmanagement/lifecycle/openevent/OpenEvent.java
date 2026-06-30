package org.pragmatica.example.ticketing.eventmanagement.lifecycle.openevent;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.shared.EventId;


/// Use case: move a draft event to 'on_sale' (guarded transition, no fact published).
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `lifecycle` -> use
/// case `open-event`. One use case, one `Request`/`Response` pair, one `execute` method.
@Slice
public interface OpenEvent {
    @SuppressWarnings("JBCT-VO-01")
    record Request(String event) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String event) {}

    sealed interface OpenEventError extends Cause {
        record EventNotFound() implements OpenEventError {
            @Override
            public String message() {
                return "Event not found";
            }
        }

        record AlreadyOpen() implements OpenEventError {
            @Override
            public String message() {
                return "Event is already open for sale";
            }
        }

        record StoreUnavailable() implements OpenEventError {
            @Override
            public String message() {
                return "Event management store is unavailable";
            }
        }

        static OpenEventError eventNotFound() {
            return new EventNotFound();
        }

        static OpenEventError alreadyOpen() {
            return new AlreadyOpen();
        }

        static OpenEventError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static OpenEvent openEvent(@PgSql EventStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record openEvent(EventStore store) implements OpenEvent {
            // JBCT pattern: Sequencer -- validate -> ensure event exists -> guarded open.
            @Override
            public Promise<Response> execute(Request request) {
                return EventId.eventId(request.event())
                              .async()
                              .flatMap(this::ensureEventThenOpen);
            }

            private Promise<Response> ensureEventThenOpen(EventId eventId) {
                return store.eventExists(eventId.value().value())
                            .mapError(_ -> OpenEventError.storeUnavailable())
                            .flatMap(exists -> openIfEventExists(exists, eventId));
            }

            // JBCT pattern: Condition -- route on event existence, no transformation.
            private Promise<Response> openIfEventExists(boolean exists, EventId eventId) {
                return exists
                       ? open(eventId)
                       : OpenEventError.eventNotFound().promise();
            }

            private Promise<Response> open(EventId eventId) {
                var uuid = eventId.value().value();

                return store.openEvent(uuid)
                            .mapError(_ -> OpenEventError.storeUnavailable())
                            .flatMap(found -> found.async(OpenEventError.alreadyOpen()))
                            .map(_ -> new Response(uuid.toString()));
            }
        }

        return new openEvent(store);
    }
}
