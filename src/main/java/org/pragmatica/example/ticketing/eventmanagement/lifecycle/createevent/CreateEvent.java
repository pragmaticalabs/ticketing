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
import org.pragmatica.example.ticketing.shared.Validation;


/// Use case: register a new event in 'draft' state.
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `lifecycle` -> use
/// case `create-event`. One use case, one `Request`/`Response` pair, one `execute` method.
@Slice
public interface CreateEvent {
    record Request(String venue, String onSaleAt) {}

    record Response(String event) {}

    /// Validated create request: a non-blank venue and a parsed ISO-8601 on-sale timestamp. Both
    /// failures surface together via `Result.all`, so a blank/garbage timestamp can no longer be
    /// persisted verbatim.
    ///
    /// `Result.all` wraps whatever it collects -- even a single failure -- in a core composite cause,
    /// which the generated router cannot match against this slice's own types and would report as
    /// HTTP 500. The closing `mapError` unwraps it back to the first failing field's local cause, which
    /// is what makes the `HTTP_400` mapping in routes.toml live.
    record ValidCreateEvent(String venue, IsoDateTime onSaleAt) {
        static Result<ValidCreateEvent> validCreateEvent(Request request) {
            return Result.all(Verify.ensure(request.venue(),
                                            Verify.Is::present,
                                            CreateEventError.blankVenue()),
                              IsoDateTime.isoDateTime(request.onSaleAt()).mapError(_ -> CreateEventError.malformedOnSaleAt(request.onSaleAt())))
                         .map(ValidCreateEvent::new)
                         .mapError(Validation::firstFailure);
        }
    }

    Promise<Response> execute(Request request);

    sealed interface CreateEventError extends Cause {
        /// Fixed-message request fields this slice rejects as unparseable. Every constant here is an HTTP 400 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 400 must not be added to it, and one
        /// `*FieldRejected*` pattern maps the whole enum.
        enum FieldRejected implements CreateEventError {
            BLANK_VENUE("Venue must not be blank");
            private final String message;
            FieldRejected(String message) {
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
        enum ServiceUnavailable implements CreateEventError {
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

        record MalformedOnSaleAt(String raw) implements CreateEventError {
            @Override
            public String message() {
                return "On-sale time is not a valid ISO-8601 timestamp: " + raw;
            }
        }

        static CreateEventError blankVenue() {
            return FieldRejected.BLANK_VENUE;
        }

        static CreateEventError malformedOnSaleAt(String raw) {
            return new MalformedOnSaleAt(raw);
        }

        static CreateEventError storeUnavailable() {
            return ServiceUnavailable.EVENT_MANAGEMENT_STORE;
        }
    }

    static CreateEvent createEvent(@PgSql EventStore store) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
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
                                         valid.onSaleAt().toString())
                            .mapError(_ -> CreateEventError.storeUnavailable())
                            .map(_ -> new Response(uuid.toString()));
            }
        }

        return new createEvent(store);
    }
}
