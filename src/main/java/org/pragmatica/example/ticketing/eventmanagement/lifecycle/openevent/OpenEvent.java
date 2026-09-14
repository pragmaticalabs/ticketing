package org.pragmatica.example.ticketing.eventmanagement.lifecycle.openevent;

import java.util.UUID;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.eventmanagement.EventStatus;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.eventmanagement.EventStore.RowId;
import org.pragmatica.example.ticketing.shared.EventId;


/// Use case: move a draft event to 'on_sale' (guarded transition, no fact published).
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `lifecycle` -> use
/// case `open-event`. One use case, one `Request`/`Response` pair, one `execute` method.
///
/// Guarantee actually earned: the guarded `UPDATE ... AND status = 'draft'` is the sole authority on
/// whether the event opened. It refuses silently, so the reason is established by reading the event back
/// -- a *separate* statement, and therefore a best-effort diagnosis. Every refusal reason is now
/// distinct: a missing event is [OpenEventError.EntityMissing#EVENT], an event already selling is
/// [OpenEventError.LifecycleConflict#EVENT_ALREADY_OPEN], a cancelled event is
/// [OpenEventError.LifecycleConflict#EVENT_CANCELLED],
/// and a diagnosis that disagrees with the refusal is [OpenEventError.TransitionRaced]. Previously every
/// refusal was reported as "already open", which was simply false for a cancelled event.
///
/// Three of those refusals are conflicts on the event's lifecycle state, so all three map to HTTP 409 in
/// this slice's `routes.toml`. They are declared here rather than shared with `add-seat`/`cancel-event`
/// because this is the only guard that can produce both, and because the slice processor maps only
/// `Cause` types found in the routed slice's own package.
@Slice
public interface OpenEvent {
    record Request(String event) {}

    record Response(String event) {}

    Promise<Response> execute(Request request);

    sealed interface OpenEventError extends Cause {
        /// Fixed-message reads that found nothing. Every constant here is an HTTP 404 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 404 must not be added to it, and one
        /// `*EntityMissing*` pattern maps the whole enum.
        enum EntityMissing implements OpenEventError {
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
        enum ServiceUnavailable implements OpenEventError {
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

        /// Fixed-message refusals by the lifecycle guard. Every constant here is a conflict on the event's
        /// current status and therefore HTTP 409, which is why the group is named for the routing rule
        /// rather than called `General`: a cause that is *not* a 409 must not be added to it, and this
        /// slice's `routes.toml` maps the whole enum with one `*LifecycleConflict*` pattern.
        enum LifecycleConflict implements OpenEventError {
            EVENT_CANCELLED("Event is cancelled"),
            EVENT_ALREADY_OPEN("Event is already open for sale");
            private final String message;
            LifecycleConflict(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        /// The guard refused although the follow-up read still reports `draft`, the status the guard
        /// admits -- a concurrent lifecycle change won the race between the guarded UPDATE and the read
        /// that diagnosed it.
        record TransitionRaced(EventStatus status) implements OpenEventError {
            @Override
            public String message() {
                return "Event lifecycle transition raced a concurrent change; event is now " + status.dbValue();
            }
        }

        /// Client-facing validation refusal (HTTP 400): a request field could not be parsed into its
        /// domain type. Declared in this slice's own hierarchy instead of letting the shared
        /// value-object cause through, because the slice processor builds the router's error switch
        /// from the `Cause` types in this package alone -- a shared cause arrives unmatched and falls
        /// through to HTTP 500. Data-carrying, so the response names the offending field and keeps the
        /// original reason.
        record InvalidRequest(String field, String detail) implements OpenEventError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        static OpenEventError eventNotFound() {
            return EntityMissing.EVENT;
        }

        static OpenEventError alreadyOpen() {
            return LifecycleConflict.EVENT_ALREADY_OPEN;
        }

        static OpenEventError storeUnavailable() {
            return ServiceUnavailable.EVENT_MANAGEMENT_STORE;
        }

        /// The event is cancelled -- a terminal status that no longer opens for sale.
        static OpenEventError eventCancelled() {
            return LifecycleConflict.EVENT_CANCELLED;
        }

        static OpenEventError transitionRaced(EventStatus status) {
            return new TransitionRaced(status);
        }

        static OpenEventError invalidEvent(Cause cause) {
            return new InvalidRequest("event", cause.message());
        }
    }

    static OpenEvent openEvent(@PgSql EventStore store) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
        record openEvent(EventStore store) implements OpenEvent {
            // JBCT pattern: Sequencer -- validate -> guarded open.
            @Override
            public Promise<Response> execute(Request request) {
                return EventId.eventId(request.event())
                              .mapError(OpenEventError::invalidEvent)
                              .async()
                              .flatMap(this::open);
            }

            private Promise<Response> open(EventId eventId) {
                var uuid = eventId.value().value();

                return store.openEvent(uuid)
                            .mapError(_ -> OpenEventError.storeUnavailable())
                            .flatMap(opened -> completeOrExplain(opened, uuid));
            }

            // JBCT pattern: Condition -- a present projection is the applied transition; an empty one is
            // the guard refusing, which only a follow-up read can explain.
            private Promise<Response> completeOrExplain(Option<RowId> opened, UUID event) {
                return opened.map(_ -> Promise.success(new Response(event.toString())))
                             .or(() -> explainRefusal(event));
            }

            private Promise<Response> explainRefusal(UUID event) {
                return store.findEvent(event)
                            .mapError(_ -> OpenEventError.storeUnavailable())
                            .flatMap(found -> found.async(OpenEventError.eventNotFound()))
                            .flatMap(row -> refusalCause(row.status()));
            }

            // JBCT pattern: Condition -- name the refusal from the observed status. DRAFT is the guard's
            // own admitted status, so observing it means a concurrent change raced the diagnosis.
            private Promise<Response> refusalCause(EventStatus status) {
                return switch (status) {
                    case ON_SALE -> OpenEventError.alreadyOpen().promise();
                    case CANCELLED -> OpenEventError.eventCancelled().promise();
                    case DRAFT -> OpenEventError.transitionRaced(status).promise();
                };
            }
        }

        return new openEvent(store);
    }
}
