package org.pragmatica.example.ticketing.eventmanagement.convergence.markseatsold;

import java.util.UUID;

import org.pragmatica.example.ticketing.eventmanagement.FailingEventStore;
import org.pragmatica.example.ticketing.eventmanagement.InMemoryEventStore;
import org.pragmatica.example.ticketing.shared.SeatState;
import org.pragmatica.example.ticketing.shared.event.SeatSold;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class MarkSeatSoldTest {
    private final InMemoryEventStore store = new InMemoryEventStore();
    private final MarkSeatSold slice = MarkSeatSold.markSeatSold(store);
    private final UUID event = UUID.randomUUID();

    private UUID availableSeat() {
        var seat = UUID.randomUUID();

        store.insertEvent(event, "Wembley Arena", "2026-07-01T19:00:00Z").await();
        store.insertSeat(seat, event, "A", "12", 7, "STANDARD").await();

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

    private static SeatSold factFor(UUID seat, long version) {
        return new SeatSold(seat.toString(),
                            UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(),
                            version);
    }

    @Test
    void execute_availableSeat_marksSold() {
        var seat = availableSeat();

        slice.execute(factFor(seat, 1L)).await().onFailure(cause -> fail(cause.message()));
        assertSeatState(seat, SeatState.SOLD);
        assertSeatVersion(seat, 1L);
    }

    /// The C4 regression: `markSeatSold` used to be the one unguarded transition, so a `SeatSold` fact
    /// for a seat deliberately held back from sale silently overwrote it.
    @Test
    void markSeatSold_blockedSeat_doesNotOverwrite() {
        var seat = UUID.randomUUID();

        store.withSeat(seat, event, SeatState.BLOCKED);
        slice.execute(factFor(seat, 1L))
             .await()
             .onSuccess(_ -> fail("Expected a convergence conflict"))
             .onFailure(cause -> assertThat(cause.message()).contains("cannot converge to sold"));
        assertSeatState(seat, SeatState.BLOCKED);
    }

    @Test
    void markSeatSold_withdrawnSeat_doesNotOverwrite() {
        var seat = UUID.randomUUID();

        store.withSeat(seat, event, SeatState.WITHDRAWN);
        slice.execute(factFor(seat, 1L))
             .await()
             .onSuccess(_ -> fail("Expected a convergence conflict"))
             .onFailure(cause -> assertThat(cause.message()).contains("cannot converge to sold"));
        assertSeatState(seat, SeatState.WITHDRAWN);
    }

    /// Guarding the transition must not break re-delivery: the same fact twice is still a success,
    /// because the seat already holds the state the fact asserts.
    @Test
    void execute_redeliveredFact_absorbsAsSuccess() {
        var seat = availableSeat();

        slice.execute(factFor(seat, 1L)).await().onFailure(cause -> fail(cause.message()));
        slice.execute(factFor(seat, 1L)).await().onFailure(cause -> fail(cause.message()));
        assertSeatState(seat, SeatState.SOLD);
        assertSeatVersion(seat, 1L);
    }

    /// The ordering guard on the authoritative row. The seat has already advanced past version 5 (sold,
    /// then released), so a sale fact carrying version 3 is stale and must not re-sell the seat.
    @Test
    void markSeatSold_staleVersion_doesNotOverwrite() {
        var seat = availableSeat();

        store.markSeatSold(5L, seat).await().onFailure(cause -> fail(cause.message()));
        store.markSeatAvailable(6L, seat).await().onFailure(cause -> fail(cause.message()));
        slice.execute(factFor(seat, 3L)).await().onFailure(cause -> fail(cause.message()));
        assertSeatState(seat, SeatState.AVAILABLE);
        assertSeatVersion(seat, 6L);
    }

    /// A stale fact is settled, not divergent: the seat is already at or beyond the fact's position, so
    /// the slice absorbs it rather than raising a false convergence conflict on a correct reordering.
    @Test
    void markSeatSold_staleVersion_absorbsAsSuccess() {
        var seat = availableSeat();

        store.markSeatSold(5L, seat).await().onFailure(cause -> fail(cause.message()));
        store.markSeatAvailable(6L, seat).await().onFailure(cause -> fail(cause.message()));
        slice.execute(factFor(seat, 3L)).await().onFailure(_ -> fail("A stale fact is settled, not a divergence"));
    }

    /// The same guard must not block progress: a sale that genuinely follows applies and stores its
    /// version.
    @Test
    void markSeatSold_newerVersion_appliesTransition() {
        var seat = availableSeat();

        store.markSeatSold(5L, seat).await().onFailure(cause -> fail(cause.message()));
        store.markSeatAvailable(6L, seat).await().onFailure(cause -> fail(cause.message()));
        slice.execute(factFor(seat, 9L)).await().onFailure(cause -> fail(cause.message()));
        assertSeatState(seat, SeatState.SOLD);
        assertSeatVersion(seat, 9L);
    }

    @Test
    void execute_unknownSeat_returnsSeatNotFound() {
        slice.execute(factFor(UUID.randomUUID(),
                              1L))
             .await()
             .onSuccess(_ -> fail("Expected SeatNotFound"))
             .onFailure(cause -> assertThat(cause.message()).contains("unknown seat"));
    }

    @Test
    void execute_malformedFact_discardsFact() {
        slice.execute(new SeatSold("not-a-uuid",
                                   UUID.randomUUID().toString(),
                                   UUID.randomUUID().toString(),
                                   1L))
             .await()
             .onFailure(cause -> fail(cause.message()));
    }

    /// A store outage is no longer swallowed: the failed `Promise` is the only evidence it produces.
    @Test
    void execute_storeFails_propagatesFailure() {
        var failing = MarkSeatSold.markSeatSold(FailingEventStore.allOperationsFail());

        failing.execute(factFor(UUID.randomUUID(),
                                1L))
               .await()
               .onSuccess(_ -> fail("Expected the store failure to propagate"))
               .onFailure(cause -> assertThat(cause.message()).contains("store down"));
    }
}
