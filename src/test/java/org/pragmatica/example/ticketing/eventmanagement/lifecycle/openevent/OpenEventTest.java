package org.pragmatica.example.ticketing.eventmanagement.lifecycle.openevent;

import java.util.UUID;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.eventmanagement.EventStatus;
import org.pragmatica.example.ticketing.eventmanagement.EventStore.RowId;
import org.pragmatica.example.ticketing.eventmanagement.FailingEventStore;
import org.pragmatica.example.ticketing.eventmanagement.InMemoryEventStore;
import org.pragmatica.example.ticketing.eventmanagement.lifecycle.openevent.OpenEvent.OpenEventError;
import org.pragmatica.example.ticketing.eventmanagement.lifecycle.openevent.OpenEvent.Request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class OpenEventTest {
    private final InMemoryEventStore store = new InMemoryEventStore();
    private final OpenEvent slice = OpenEvent.openEvent(store);

    /// Models the race window the guard cannot close: the guarded `UPDATE` matches no row, yet the
    /// follow-up read still reports `draft` -- the very status the guard admits.
    private static final class RefusingOpenStore extends InMemoryEventStore {
        @Override
        public Promise<Option<RowId>> openEvent(UUID id) {
            return Promise.success(Option.none());
        }
    }

    private UUID draftEvent() {
        var id = UUID.randomUUID();

        store.insertEvent(id, "Wembley Arena", "2026-07-01T19:00:00Z").await();

        return id;
    }

    @Test
    void execute_draftEvent_marksOnSale() {
        var id = draftEvent();

        slice.execute(new Request(id.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.event()).isEqualTo(id.toString()));
        store.eventStatusOf(id)
             .onEmpty(() -> fail("Expected event " + id + " to exist"))
             .onPresent(status -> assertThat(status).isEqualTo(EventStatus.ON_SALE));
    }

    @Test
    void execute_unknownEvent_returnsEventNotFound() {
        slice.execute(new Request(UUID.randomUUID().toString()))
             .await()
             .onSuccess(_ -> fail("Expected EventNotFound"))
             .onFailure(cause -> assertThat(cause.message()).contains("not found"));
    }

    @Test
    void execute_alreadyOpenEvent_returnsAlreadyOpen() {
        var id = draftEvent();

        slice.execute(new Request(id.toString())).await();
        slice.execute(new Request(id.toString()))
             .await()
             .onSuccess(_ -> fail("Expected AlreadyOpen"))
             .onFailure(cause -> assertThat(cause.message()).contains("already open"));
    }

    /// The H8(b) regression: every refused open was reported as "already open", so a cancelled event
    /// produced a 409 stating something that was simply not true.
    @Test
    void openEvent_cancelledEvent_returnsCancelledNotAlreadyOpen() {
        var id = draftEvent();

        store.cancelEvent(id).await();
        slice.execute(new Request(id.toString()))
             .await()
             .onSuccess(_ -> fail("Expected a cancelled event to refuse opening"))
             .onFailure(OpenEventTest::assertCancelledAndNotAlreadyOpen);
    }

    private static void assertCancelledAndNotAlreadyOpen(Cause cause) {
        assertThat(cause).isEqualTo(OpenEventError.LifecycleConflict.EVENT_CANCELLED);
        assertThat(cause.message()).contains("cancelled").doesNotContain("already open");
    }

    /// A concurrent lifecycle change can land between the guarded `UPDATE` and the read that diagnoses it,
    /// leaving a refusal the read cannot account for. That window is reported as its own cause rather than
    /// being folded into `AlreadyOpen`.
    @Test
    void openEvent_refusedWhileStillDraft_returnsTransitionRaced() {
        var racing = new RefusingOpenStore();
        var id = UUID.randomUUID();

        racing.insertEvent(id, "Wembley Arena", "2026-07-01T19:00:00Z").await();
        OpenEvent.openEvent(racing)
                 .execute(new Request(id.toString()))
                 .await()
                 .onSuccess(_ -> fail("Expected the refused transition to be diagnosed as a race"))
                 .onFailure(cause -> assertThat(cause).isInstanceOf(OpenEventError.TransitionRaced.class));
    }

    /// Both lifecycle refusals are client-visible conflicts. They used to live outside this slice's package,
    /// so the generated router could not see them and fell through to HTTP 500; these two tests pin the 409.
    @Test
    void errorMapper_eventCancelled_mapsToConflict() {
        assertMappedStatus(OpenEventError.eventCancelled(), HttpStatus.CONFLICT);
    }

    @Test
    void errorMapper_transitionRaced_mapsToConflict() {
        assertMappedStatus(OpenEventError.transitionRaced(EventStatus.DRAFT), HttpStatus.CONFLICT);
    }

    private static void assertMappedStatus(Cause cause, HttpStatus expected) {
        assertThat(new OpenEventRoutes().errorMapper().map(cause).status()).isEqualTo(expected);
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
        var failing = OpenEvent.openEvent(FailingEventStore.allOperationsFail());

        failing.execute(new Request(UUID.randomUUID().toString()))
               .await()
               .onSuccess(_ -> fail("Expected StoreUnavailable"))
               .onFailure(cause -> assertThat(cause.message()).contains("unavailable"));
    }
}
