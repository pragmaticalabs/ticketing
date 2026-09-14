package org.pragmatica.example.ticketing.eventmanagement.lifecycle.cancelevent;

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


/// Use case: withdraw an event (guarded transition to 'cancelled').
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `lifecycle` -> use
/// case `cancel-event`. One use case, one `Request`/`Response` pair, one `execute` method.
///
/// Guarantee actually earned -- read this before assuming a cascade: this use case sets
/// `events.status = 'cancelled'` on a single row, and **nothing else**. It does not release or withdraw
/// the event's seats, does not cancel or refund any booking, and publishes no fact, so nothing
/// downstream reacts to it. A cancelled event's seats keep whatever state they had, and booking will
/// still find them. Wiring that cascade means driving booking's compensation from
/// eventmanagement, which crosses a subsystem boundary and is deliberately not done here.
///
/// The transition is guarded (`... AND status <> 'cancelled'`), so it can no longer re-stamp a terminal
/// row. Cancelling an already-cancelled event is reported as success: the caller's intent -- that the
/// event be cancelled -- already holds, and inventing a failure for a satisfied postcondition would be
/// less true than the success. The follow-up read that establishes this is a separate statement, so a
/// refusal that the read cannot account for surfaces as [CancelEventError.TransitionRaced] (HTTP 409).
/// That is this slice's *only* lifecycle refusal -- a cancelled event is its success path, so it never
/// reports "event is cancelled" as a failure the way `open-event` and `add-seat` do, and its closed
/// failure set says so.
@Slice
public interface CancelEvent {
    record Request(String event) {}

    record Response(String event) {}

    Promise<Response> execute(Request request);

    sealed interface CancelEventError extends Cause {
        /// Fixed-message reads that found nothing. Every constant here is an HTTP 404 in this
        /// slice's `routes.toml`, which is why the group is named for that routing rule rather than
        /// `General`: a cause that is *not* a 404 must not be added to it, and one
        /// `*EntityMissing*` pattern maps the whole enum.
        enum EntityMissing implements CancelEventError {
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
        enum ServiceUnavailable implements CancelEventError {
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

        /// The guard refused although the follow-up read reports a status the guard admits -- a concurrent
        /// lifecycle change won the race between the guarded UPDATE and the read that diagnosed it.
        record TransitionRaced(EventStatus status) implements CancelEventError {
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
        record InvalidRequest(String field, String detail) implements CancelEventError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        static CancelEventError eventNotFound() {
            return EntityMissing.EVENT;
        }

        static CancelEventError storeUnavailable() {
            return ServiceUnavailable.EVENT_MANAGEMENT_STORE;
        }

        static CancelEventError transitionRaced(EventStatus status) {
            return new TransitionRaced(status);
        }

        static CancelEventError invalidEvent(Cause cause) {
            return new InvalidRequest("event", cause.message());
        }
    }

    static CancelEvent cancelEvent(@PgSql EventStore store) {
        // JBCT-ORD-01: the slice-implementation record lives inside its own factory, so it can never precede it.
        @SuppressWarnings({"JBCT-SEQ-01", "JBCT-ORD-01"})
        record cancelEvent(EventStore store) implements CancelEvent {
            // JBCT pattern: Sequencer -- validate -> guarded cancel.
            @Override
            public Promise<Response> execute(Request request) {
                return EventId.eventId(request.event())
                              .mapError(CancelEventError::invalidEvent)
                              .async()
                              .flatMap(this::doCancel);
            }

            private Promise<Response> doCancel(EventId eventId) {
                var uuid = eventId.value().value();

                return store.cancelEvent(uuid)
                            .mapError(_ -> CancelEventError.storeUnavailable())
                            .flatMap(cancelled -> completeOrExplain(cancelled, uuid));
            }

            // JBCT pattern: Condition -- a present projection is the applied transition; an empty one is
            // the guard refusing, which only a follow-up read can explain.
            private Promise<Response> completeOrExplain(Option<RowId> cancelled, UUID event) {
                return cancelled.map(_ -> Promise.success(cancelledResponse(event)))
                                .or(() -> explainRefusal(event));
            }

            /// The response for an event that is cancelled, whether this call performed the transition or
            /// found it already satisfied. Returns the value rather than a `Promise` because it cannot
            /// fail -- both callers supply their own success wrapping.
            private Response cancelledResponse(UUID event) {
                return new Response(event.toString());
            }

            private Promise<Response> explainRefusal(UUID event) {
                return store.findEvent(event)
                            .mapError(_ -> CancelEventError.storeUnavailable())
                            .flatMap(found -> found.async(CancelEventError.eventNotFound()))
                            .flatMap(row -> refusalOutcome(row.status(),
                                                           event));
            }

            // JBCT pattern: Condition -- the guard admits every non-cancelled status, so a refusal on an
            // existing row means the event is already cancelled, which satisfies the caller. Any other
            // status means a concurrent change raced the diagnosis.
            private Promise<Response> refusalOutcome(EventStatus status, UUID event) {
                return status == EventStatus.CANCELLED
                       ? Promise.success(cancelledResponse(event))
                       : CancelEventError.transitionRaced(status).promise();
            }
        }

        return new cancelEvent(store);
    }
}
