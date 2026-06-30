package org.pragmatica.example.ticketing.availability.projection.projectseatreleased;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.availability.projection.SeatProjectionStore;
import org.pragmatica.example.ticketing.shared.event.SeatReleased;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class ProjectSeatReleasedTest {
    private static final class FakeStore implements SeatProjectionStore {
        private final Map<UUID, String> statuses = new HashMap<>();

        // Test-only inspection of the projected state the upsert wrote.
        Option<String> statusOf(UUID seatId) {
            return Option.option(statuses.get(seatId));
        }

        @Override
        public Promise<Unit> upsertStatus(UUID seatId, UUID eventId, String status) {
            statuses.put(seatId, status);

            return Promise.UNIT;
        }
    }

    // Store whose every operation fails, to drive the subscriber's recover-to-Unit path.
    private static final class FailingStore implements SeatProjectionStore {
        @Override
        public Promise<Unit> upsertStatus(UUID seatId, UUID eventId, String status) {
            return Causes.cause("store unavailable").promise();
        }
    }

    private final FakeStore store = new FakeStore();
    private final ProjectSeatReleased projection = ProjectSeatReleased.projectSeatReleased(store);

    @Test
    void execute_seatReleasedFact_convergesToAvailable() {
        var seat = UUID.randomUUID();
        var event = UUID.randomUUID();

        store.upsertStatus(seat, event, "sold").await();
        projection.execute(new SeatReleased(seat.toString(), event.toString())).await().onFailure(cause -> fail(cause.message()));
        store.statusOf(seat).onEmpty(() -> fail("Expected projected status")).onPresent(status -> assertThat(status).isEqualTo("available"));
    }

    @Test
    void execute_malformedFact_recoversToUnit() {
        projection.execute(new SeatReleased("not-a-uuid", "not-a-uuid")).await().onFailure(cause -> fail("Expected recovery to Unit")).onSuccess(u -> assertThat(u).isEqualTo(Unit.unit()));
    }

    @Test
    void execute_storeFails_recoversToUnit() {
        var failing = ProjectSeatReleased.projectSeatReleased(new FailingStore());

        failing.execute(new SeatReleased(UUID.randomUUID().toString(),
                                         UUID.randomUUID().toString())).await().onFailure(cause -> fail("Expected recovery to Unit")).onSuccess(u -> assertThat(u).isEqualTo(Unit.unit()));
    }
}
