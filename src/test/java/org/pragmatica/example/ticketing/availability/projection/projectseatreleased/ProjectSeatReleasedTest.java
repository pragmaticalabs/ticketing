package org.pragmatica.example.ticketing.availability.projection.projectseatreleased;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.availability.projection.SeatProjectionStore;
import org.pragmatica.example.ticketing.shared.SeatState;
import org.pragmatica.example.ticketing.shared.event.SeatReleased;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class ProjectSeatReleasedTest {
    /// Fake of the projection store that models BOTH halves of the real statement: the upsert on
    /// `seat_id`, and the `WHERE seat_availability.version < EXCLUDED.version` guard that makes it
    /// ordered rather than last-write-wins. A fake without the guard would report a passing test for
    /// exactly the reordering bug the guard exists to stop.
    private static final class FakeStore implements SeatProjectionStore {
        private record Projected(SeatState state, long version) {}

        private final Map<UUID, Projected> statuses = new HashMap<>();

        // Test-only inspection of the projected state the upsert wrote.
        Option<SeatState> statusOf(UUID seatId) {
            return Option.option(statuses.get(seatId)).map(Projected::state);
        }

        // Test-only inspection of the version the projected row is pinned at.
        Option<Long> versionOf(UUID seatId) {
            return Option.option(statuses.get(seatId)).map(Projected::version);
        }

        @Override
        public Promise<Unit> upsertStatus(UUID seatId, UUID eventId, SeatState status, long version) {
            statuses.merge(seatId, new Projected(status, version), FakeStore::advancing);

            return Promise.UNIT;
        }

        /// The ON CONFLICT branch: keep the stored row unless the incoming fact advances it.
        private static Projected advancing(Projected stored, Projected incoming) {
            return incoming.version() > stored.version()
                   ? incoming
                   : stored;
        }
    }

    // Store whose every operation fails, to drive the subscriber's recover-to-Unit path.
    private static final class FailingStore implements SeatProjectionStore {
        @Override
        public Promise<Unit> upsertStatus(UUID seatId, UUID eventId, SeatState status, long version) {
            return Causes.cause("store unavailable").promise();
        }
    }

    private final FakeStore store = new FakeStore();
    private final ProjectSeatReleased projection = ProjectSeatReleased.projectSeatReleased(store);
    private final UUID seat = UUID.randomUUID();
    private final UUID event = UUID.randomUUID();

    private SeatReleased factFor(UUID seat, long version) {
        return new SeatReleased(seat.toString(), event.toString(), version);
    }

    private void assertProjected(SeatState expectedState, long expectedVersion) {
        store.statusOf(seat)
             .onEmpty(() -> fail("Expected projected status"))
             .onPresent(status -> assertThat(status).isEqualTo(expectedState));
        store.versionOf(seat)
             .onEmpty(() -> fail("Expected projected version"))
             .onPresent(version -> assertThat(version).isEqualTo(expectedVersion));
    }

    @Test
    void execute_seatReleasedFact_convergesToAvailable() {
        store.upsertStatus(seat, event, SeatState.SOLD, 1L).await();
        projection.execute(factFor(seat, 2L)).await().onFailure(cause -> fail(cause.message()));
        assertProjected(SeatState.AVAILABLE, 2L);
    }

    @Test
    void execute_malformedFact_recoversToUnit() {
        projection.execute(new SeatReleased("not-a-uuid", "not-a-uuid", 1L))
                  .await()
                  .onFailure(cause -> fail("Expected recovery to Unit"))
                  .onSuccess(u -> assertThat(u).isEqualTo(Unit.unit()));
    }

    @Test
    void execute_storeFails_recoversToUnit() {
        var failing = ProjectSeatReleased.projectSeatReleased(new FailingStore());

        failing.execute(factFor(seat, 1L))
               .await()
               .onFailure(cause -> fail("Expected recovery to Unit"))
               .onSuccess(u -> assertThat(u).isEqualTo(Unit.unit()));
    }

    /// The reordering the whole change exists for: the seat was sold at version 7 and the release that
    /// preceded that sale is delivered afterwards. Its lower version loses, so the read model keeps
    /// showing the seat as sold instead of offering a seat somebody already bought.
    @Test
    void upsertStatus_releaseOvertakenBySale_leavesProjectionSold() {
        store.upsertStatus(seat, event, SeatState.SOLD, 7L).await();
        projection.execute(factFor(seat, 4L)).await().onFailure(cause -> fail(cause.message()));
        assertProjected(SeatState.SOLD, 7L);
    }

    /// The same guard must not block progress: a release that genuinely follows the sale applies.
    @Test
    void upsertStatus_newerVersion_appliesProjection() {
        store.upsertStatus(seat, event, SeatState.SOLD, 7L).await();
        projection.execute(factFor(seat, 8L)).await().onFailure(cause -> fail(cause.message()));
        assertProjected(SeatState.AVAILABLE, 8L);
    }

    /// Equal versions are the redelivery case: already reflected, so re-applying it is a no-op.
    @Test
    void upsertStatus_redeliveredFact_leavesProjectionUnchanged() {
        projection.execute(factFor(seat, 4L)).await().onFailure(cause -> fail(cause.message()));
        projection.execute(factFor(seat, 4L)).await().onFailure(cause -> fail(cause.message()));
        assertProjected(SeatState.AVAILABLE, 4L);
    }
}
