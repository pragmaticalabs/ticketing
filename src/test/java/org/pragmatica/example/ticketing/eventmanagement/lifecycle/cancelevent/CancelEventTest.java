package org.pragmatica.example.ticketing.eventmanagement.lifecycle.cancelevent;

import java.util.UUID;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.eventmanagement.EventStatus;
import org.pragmatica.example.ticketing.eventmanagement.EventStore.RowId;
import org.pragmatica.example.ticketing.eventmanagement.FailingEventStore;
import org.pragmatica.example.ticketing.eventmanagement.FailingEventStore.FailOp;
import org.pragmatica.example.ticketing.eventmanagement.InMemoryEventStore;
import org.pragmatica.example.ticketing.eventmanagement.lifecycle.cancelevent.CancelEvent.CancelEventError;
import org.pragmatica.example.ticketing.eventmanagement.lifecycle.cancelevent.CancelEvent.Request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class CancelEventTest {
    private final InMemoryEventStore store = new InMemoryEventStore();
    private final CancelEvent slice = CancelEvent.cancelEvent(store);

    /// Models the race window the guard cannot close: the guarded `UPDATE` matches no row, yet the
    /// follow-up read still reports a status the guard admits (`draft`), so the refusal has no settled
    /// explanation.
    private static final class RefusingCancelStore extends InMemoryEventStore {
        @Override
        public Promise<Option<RowId>> cancelEvent(UUID id) {
            return Promise.success(Option.none());
        }
    }

    private static UUID draftEventIn(InMemoryEventStore target) {
        var id = UUID.randomUUID();

        target.insertEvent(id, "Wembley Arena", "2026-07-01T19:00:00Z").await();

        return id;
    }

    private UUID draftEvent() {
        return draftEventIn(store);
    }

    @Test
    void execute_draftEvent_cancels() {
        var id = draftEvent();

        slice.execute(new Request(id.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.event()).isEqualTo(id.toString()));
        store.eventStatusOf(id)
             .onEmpty(() -> fail("Expected event " + id + " to exist"))
             .onPresent(status -> assertThat(status).isEqualTo(EventStatus.CANCELLED));
    }

    @Test
    void execute_onSaleEvent_cancels() {
        var id = draftEvent();

        store.openEvent(id).await();
        slice.execute(new Request(id.toString())).await().onFailure(cause -> fail(cause.message()));
        store.eventStatusOf(id)
             .onEmpty(() -> fail("Expected event " + id + " to exist"))
             .onPresent(status -> assertThat(status).isEqualTo(EventStatus.CANCELLED));
    }

    /// Cancelling twice must remain a success -- the caller's intent already holds.
    @Test
    void execute_alreadyCancelledEvent_reportsSuccess() {
        var id = draftEvent();

        slice.execute(new Request(id.toString())).await();
        slice.execute(new Request(id.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.event()).isEqualTo(id.toString()));
    }

    /// The (c) regression: `cancelEvent` was an unguarded `UPDATE ... WHERE id = :id`, so a repeat cancel
    /// re-stamped the terminal row and reported success without ever looking at the current status. With
    /// the guard in place the repeat matches no row, so the slice *must* consult the current status to
    /// decide -- which a failing `findEvent` makes observable.
    @Test
    void cancelEvent_repeatCancel_consultsCurrentStatus() {
        var failing = new FailingEventStore(FailOp.FIND_EVENT);
        var id = draftEventIn(failing);
        var failingSlice = CancelEvent.cancelEvent(failing);

        failingSlice.execute(new Request(id.toString())).await().onFailure(cause -> fail(cause.message()));
        failingSlice.execute(new Request(id.toString()))
                    .await()
                    .onSuccess(_ -> fail("Expected the repeat cancel to be refused and then diagnosed"))
                    .onFailure(cause -> assertThat(cause.message()).contains("unavailable"));
    }

    /// A concurrent lifecycle change can land between the guarded `UPDATE` and the read that diagnoses it:
    /// the guard refuses, yet the event still reads `draft`, which the guard admits. That window has no
    /// settled explanation and must not be passed off as the idempotent "already cancelled" success.
    @Test
    void cancelEvent_refusedWhileStillDraft_returnsTransitionRaced() {
        var racing = new RefusingCancelStore();
        var id = draftEventIn(racing);

        CancelEvent.cancelEvent(racing)
                   .execute(new Request(id.toString()))
                   .await()
                   .onSuccess(_ -> fail("Expected the refused transition to be diagnosed as a race"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(CancelEventError.TransitionRaced.class));
    }

    /// A raced transition is a client-visible conflict, not a server fault. The cause used to live outside
    /// this slice's package, so the generated router could not see it and fell through to HTTP 500.
    @Test
    void errorMapper_transitionRaced_mapsToConflict() {
        assertThat(new CancelEventRoutes().errorMapper()
                                          .map(CancelEventError.transitionRaced(EventStatus.DRAFT))
                                          .status()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void execute_unknownEvent_returnsEventNotFound() {
        slice.execute(new Request(UUID.randomUUID().toString()))
             .await()
             .onSuccess(_ -> fail("Expected EventNotFound"))
             .onFailure(cause -> assertThat(cause.message()).contains("not found"));
    }

    @Test
    void execute_malformedId_returnsValidationFailure() {
        slice.execute(new Request("not-a-uuid"))
             .await()
             .onSuccess(_ -> fail("Expected validation failure"))
             .onFailure(cause -> assertThat(cause.message()).contains("valid UUID"));
    }

    @Test
    void execute_storeFails_returnsStoreUnavailable() {
        var failing = CancelEvent.cancelEvent(FailingEventStore.allOperationsFail());

        failing.execute(new Request(UUID.randomUUID().toString()))
               .await()
               .onSuccess(_ -> fail("Expected StoreUnavailable"))
               .onFailure(cause -> assertThat(cause.message()).contains("unavailable"));
    }
}
