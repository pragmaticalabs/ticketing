package org.pragmatica.example.ticketing.eventmanagement.lifecycle.cancelevent;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.shared.EventId;


/// Use case: withdraw an event (guarded transition to 'cancelled').
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `lifecycle` -> use
/// case `cancel-event`. One use case, one `Request`/`Response` pair, one `execute` method.
@Slice
public interface CancelEvent {
    @SuppressWarnings("JBCT-VO-01")
    record Request(String event) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String event) {}

    sealed interface CancelEventError extends Cause {
        record EventNotFound() implements CancelEventError {
            @Override
            public String message() {
                return "Event not found";
            }
        }

        record StoreUnavailable() implements CancelEventError {
            @Override
            public String message() {
                return "Event management store is unavailable";
            }
        }

        static CancelEventError eventNotFound() {
            return new EventNotFound();
        }

        static CancelEventError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static CancelEvent cancelEvent(@PgSql EventStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record cancelEvent(EventStore store) implements CancelEvent {
            // JBCT pattern: Sequencer -- validate -> guarded cancel update.
            @Override
            public Promise<Response> execute(Request request) {
                return EventId.eventId(request.event())
                              .async()
                              .flatMap(this::doCancel);
            }

            private Promise<Response> doCancel(EventId eventId) {
                var uuid = eventId.value().value();

                return store.cancelEvent(uuid)
                            .mapError(_ -> CancelEventError.storeUnavailable())
                            .flatMap(found -> found.async(CancelEventError.eventNotFound()))
                            .map(_ -> new Response(uuid.toString()));
            }
        }

        return new cancelEvent(store);
    }
}
