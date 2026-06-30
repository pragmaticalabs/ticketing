package org.pragmatica.example.ticketing.eventmanagement.lifecycle.createevent;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.vo.IsoDateTime;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.shared.EventId;


/// Use case: register a new event in 'draft' state.
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `lifecycle` -> use
/// case `create-event`. One use case, one `Request`/`Response` pair, one `execute` method.
@Slice
public interface CreateEvent {
    @SuppressWarnings("JBCT-VO-01")
    record Request(String venue, String onSaleAt) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String event) {}

    /// Validated create request: a non-blank venue and a parsed ISO-8601 on-sale timestamp. Both
    /// failures surface together via `Result.all`, so a blank/garbage timestamp can no longer be
    /// persisted verbatim.
    record ValidCreateEvent(String venue, IsoDateTime onSaleAt) {
        static Result<ValidCreateEvent> validCreateEvent(Request request) {
            return Result.all(Verify.ensure(request.venue(),
                                            Verify.Is::present,
                                            CreateEventError.blankVenue()),
                              IsoDateTime.isoDateTime(request.onSaleAt()).mapError(_ -> CreateEventError.malformedOnSaleAt(request.onSaleAt())))
                         .map(ValidCreateEvent::new);
        }
    }

    sealed interface CreateEventError extends Cause {
        record BlankVenue() implements CreateEventError {
            @Override
            public String message() {
                return "Venue must not be blank";
            }
        }

        record MalformedOnSaleAt(String raw) implements CreateEventError {
            @Override
            public String message() {
                return "On-sale time is not a valid ISO-8601 timestamp: " + raw;
            }
        }

        record StoreUnavailable() implements CreateEventError {
            @Override
            public String message() {
                return "Event management store is unavailable";
            }
        }

        static CreateEventError blankVenue() {
            return new BlankVenue();
        }

        static CreateEventError malformedOnSaleAt(String raw) {
            return new MalformedOnSaleAt(raw);
        }

        static CreateEventError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static CreateEvent createEvent(@PgSql EventStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record createEvent(EventStore store) implements CreateEvent {
            // JBCT pattern: Sequencer -- validate venue + on-sale time -> register event.
            @Override
            public Promise<Response> execute(Request request) {
                return ValidCreateEvent.validCreateEvent(request)
                                       .async()
                                       .flatMap(this::register);
            }

            private Promise<Response> register(ValidCreateEvent valid) {
                var uuid = EventId.eventId().value().value();

                return store.insertEvent(uuid,
                                         valid.venue(),
                                         valid.onSaleAt().toString()).mapError(_ -> CreateEventError.storeUnavailable())
                                        .map(_ -> new Response(uuid.toString()));
            }
        }

        return new createEvent(store);
    }
}
