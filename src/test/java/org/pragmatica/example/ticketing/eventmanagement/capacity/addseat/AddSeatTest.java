package org.pragmatica.example.ticketing.eventmanagement.capacity.addseat;

import java.util.UUID;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.example.ticketing.eventmanagement.FailingEventStore;
import org.pragmatica.example.ticketing.eventmanagement.InMemoryEventStore;
import org.pragmatica.example.ticketing.eventmanagement.capacity.addseat.AddSeat.AddSeatError;
import org.pragmatica.example.ticketing.eventmanagement.capacity.addseat.AddSeat.Request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class AddSeatTest {
    private final InMemoryEventStore store = new InMemoryEventStore();
    private final AddSeat slice = AddSeat.addSeat(store);

    private UUID draftEvent() {
        var id = UUID.randomUUID();

        store.insertEvent(id, "Wembley Arena", "2026-07-01T19:00:00Z").await();

        return id;
    }

    private static Request requestFor(UUID event) {
        return new Request(event.toString(), "A", "12", 7, "STANDARD");
    }

    @Test
    void execute_draftEvent_returnsSeatId() {
        slice.execute(requestFor(draftEvent()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.seat()).isNotBlank());
    }

    @Test
    void execute_onSaleEvent_returnsSeatId() {
        var id = draftEvent();

        store.openEvent(id).await();
        slice.execute(requestFor(id))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.seat()).isNotBlank());
    }

    /// The H8(a) regression: the gate used to check only that the event *existed*, so a cancelled event
    /// -- a terminal status -- happily grew capacity.
    @Test
    void addSeat_cancelledEvent_isRefused() {
        var id = draftEvent();

        store.cancelEvent(id).await();
        slice.execute(requestFor(id))
             .await()
             .onSuccess(_ -> fail("Expected a cancelled event to refuse new capacity"))
             .onFailure(AddSeatTest::assertCancelledRefusal);
    }

    private static void assertCancelledRefusal(Cause cause) {
        assertThat(cause).isEqualTo(AddSeatError.LifecycleConflict.EVENT_CANCELLED);
        assertThat(cause.message()).contains("cancelled");
    }

    /// Refusing a cancelled event is a lifecycle conflict, not a server fault. The cause used to live
    /// outside this slice's package, so the generated router could not see it and fell through to HTTP 500.
    @Test
    void errorMapper_eventCancelled_mapsToConflict() {
        assertThat(new AddSeatRoutes().errorMapper().map(AddSeatError.eventCancelled()).status()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void execute_unknownEvent_returnsEventNotFound() {
        slice.execute(requestFor(UUID.randomUUID()))
             .await()
             .onSuccess(_ -> fail("Expected EventNotFound"))
             .onFailure(cause -> assertThat(cause.message()).contains("not found"));
    }

    @Test
    void execute_unknownTier_returnsValidationFailure() {
        slice.execute(new Request(UUID.randomUUID().toString(),
                                  "A",
                                  "12",
                                  7,
                                  "GOLD"))
             .await()
             .onSuccess(_ -> fail("Expected validation failure"))
             .onFailure(cause -> assertThat(cause.message()).contains("tier"));
    }

    @Test
    void execute_malformedEvent_returnsValidationFailure() {
        slice.execute(new Request("not-a-uuid", "A", "12", 7, "STANDARD"))
             .await()
             .onSuccess(_ -> fail("Expected validation failure"))
             .onFailure(cause -> assertThat(cause.message()).contains("valid UUID"));
    }

    @Test
    void execute_storeFails_returnsStoreUnavailable() {
        var failing = AddSeat.addSeat(FailingEventStore.allOperationsFail());

        failing.execute(requestFor(UUID.randomUUID()))
               .await()
               .onSuccess(_ -> fail("Expected StoreUnavailable"))
               .onFailure(cause -> assertThat(cause.message()).contains("unavailable"));
    }

    /// See BuyTicketTest for why this contract exists. The multi-field variant is what holds the
    /// `Result.all` composite unwrap in place.
    @Test
    void validAddSeat_malformedEvent_returnsSliceLocalInvalidRequest() {
        AddSeat.ValidAddSeat.validAddSeat(new AddSeat.Request("not-a-uuid", "A", "1", 1, "STANDARD"))
                            .onSuccess(_ -> fail("Expected validation to fail"))
                            .onFailure(cause -> assertThat(cause).isInstanceOf(AddSeat.AddSeatError.InvalidRequest.class));
    }

    @Test
    void validAddSeat_multipleInvalidFields_returnsSliceLocalCauseNotComposite() {
        AddSeat.ValidAddSeat.validAddSeat(new AddSeat.Request("not-a-uuid", "", "", 1, "NO_SUCH_TIER"))
                            .onSuccess(_ -> fail("Expected validation to fail"))
                            .onFailure(cause -> assertThat(cause.getClass().getName()).doesNotContain("org.pragmatica.example.ticketing.shared.")
                                                          .doesNotContain("Composite"));
    }
}
