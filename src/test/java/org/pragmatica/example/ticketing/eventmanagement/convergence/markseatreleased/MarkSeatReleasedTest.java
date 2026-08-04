package org.pragmatica.example.ticketing.eventmanagement.convergence.markseatreleased;

import java.util.UUID;

import org.pragmatica.example.ticketing.eventmanagement.FailingEventStore;
import org.pragmatica.example.ticketing.eventmanagement.InMemoryEventStore;
import org.pragmatica.example.ticketing.shared.SeatState;
import org.pragmatica.example.ticketing.shared.event.SeatReleased;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class MarkSeatReleasedTest {
    private final InMemoryEventStore store = new InMemoryEventStore();
    private final MarkSeatReleased slice = MarkSeatReleased.markSeatReleased(store);
    private final UUID event = UUID.randomUUID();

    private UUID soldSeat(long version) {
        var seat = UUID.randomUUID();

        store.insertEvent(event, "Wembley Arena", "2026-07-01T19:00:00Z").await();
        store.insertSeat(seat, event, "A", "12", 7, "STANDARD").await();
        store.markSeatSold(version, seat).await().onFailure(cause -> fail(cause.message()));

        return seat;
    }

    private void assertSeatState(UUID seat, SeatState expected) {
        store.seatStateOf(seat)
             .onEmpty(() -> fail("Expected seat " + seat + " to exist"))
             .onPresent(state -> assertThat(state).isEqualTo(expected));
    }

    private void assertSeatVersion(UUID seat, long expected) {
        store.seatVersionOf(seat)
             .onEmpty(() -> fail("Expected seat " + seat + " to exist"))
             .onPresent(version -> assertThat(version).isEqualTo(expected));
    }

    private static SeatReleased factFor(UUID seat, long version) {
        return new SeatReleased(seat.toString(),
                                UUID.randomUUID().toString(),
                                version);
    }

    @Test
    void execute_soldSeat_marksAvailable() {
        var seat = soldSeat(1L);

        slice.execute(factFor(seat, 2L)).await().onFailure(cause -> fail(cause.message()));
        assertSeatState(seat, SeatState.AVAILABLE);
        assertSeatVersion(seat, 2L);
    }

    /// Mirror of the C4 regression on the release side: a blocked seat is not silently freed.
    @Test
    void markSeatAvailable_blockedSeat_doesNotOverwrite() {
        var seat = UUID.randomUUID();

        store.withSeat(seat, event, SeatState.BLOCKED);
        slice.execute(factFor(seat, 2L))
             .await()
             .onSuccess(_ -> fail("Expected a convergence conflict"))
             .onFailure(cause -> assertThat(cause.message()).contains("cannot converge to available"));
        assertSeatState(seat, SeatState.BLOCKED);
    }

    @Test
    void execute_redeliveredFact_absorbsAsSuccess() {
        var seat = soldSeat(1L);

        slice.execute(factFor(seat, 2L)).await().onFailure(cause -> fail(cause.message()));
        slice.execute(factFor(seat, 2L)).await().onFailure(cause -> fail(cause.message()));
        assertSeatState(seat, SeatState.AVAILABLE);
        assertSeatVersion(seat, 2L);
    }

    /// The reordering this change exists for, on the authoritative row: the seat was sold at version 3
    /// and the release that preceded that sale is delivered afterwards. Its lower version loses, so the
    /// seat stays sold rather than being handed back to inventory under a live booking.
    @Test
    void markSeatAvailable_releaseOvertakenBySale_leavesSeatSold() {
        var seat = soldSeat(3L);

        slice.execute(factFor(seat, 2L)).await().onFailure(cause -> fail(cause.message()));
        assertSeatState(seat, SeatState.SOLD);
        assertSeatVersion(seat, 3L);
    }

    /// The overtaken release is settled, not divergent -- absorbing it is what keeps a correct
    /// reordering from being reported as a convergence conflict.
    @Test
    void markSeatAvailable_staleVersion_absorbsAsSuccess() {
        var seat = soldSeat(3L);

        slice.execute(factFor(seat, 2L)).await().onFailure(_ -> fail("A stale fact is settled, not a divergence"));
    }

    /// The same guard must not block progress: a release that genuinely follows the sale applies.
    @Test
    void markSeatAvailable_newerVersion_appliesTransition() {
        var seat = soldSeat(3L);

        slice.execute(factFor(seat, 4L)).await().onFailure(cause -> fail(cause.message()));
        assertSeatState(seat, SeatState.AVAILABLE);
        assertSeatVersion(seat, 4L);
    }

    @Test
    void execute_unknownSeat_returnsSeatNotFound() {
        slice.execute(factFor(UUID.randomUUID(),
                              2L))
             .await()
             .onSuccess(_ -> fail("Expected SeatNotFound"))
             .onFailure(cause -> assertThat(cause.message()).contains("unknown seat"));
    }

    @Test
    void execute_malformedFact_discardsFact() {
        slice.execute(new SeatReleased("not-a-uuid",
                                       UUID.randomUUID().toString(),
                                       2L))
             .await()
             .onFailure(cause -> fail(cause.message()));
    }

    @Test
    void execute_storeFails_propagatesFailure() {
        var failing = MarkSeatReleased.markSeatReleased(FailingEventStore.allOperationsFail());

        failing.execute(factFor(UUID.randomUUID(),
                                2L))
               .await()
               .onSuccess(_ -> fail("Expected the store failure to propagate"))
               .onFailure(cause -> assertThat(cause.message()).contains("store down"));
    }
}
