package org.pragmatica.example.ticketing.eventmanagement;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.shared.SeatState;


/// Shared in-memory fake of the @PgSql {@link EventStore}, used by every event-management slice test.
/// Public (and non-final so {@link FailingEventStore} can subclass it) so the deep-package slice tests
/// can reuse it instead of each keeping its own copy.
///
/// **This fake is only useful to the degree it models the same constraints the real schema does.** Each
/// method below reproduces the constraints of its `@Query` and of `V002__eventmanagement.sql` exactly:
///
///   - every lifecycle transition is guarded by the same `AND <column> = <state>` predicate as its SQL,
///     and a refused transition yields an empty projection just as `RETURNING id` would;
///   - the convergence transitions carry the SECOND predicate too (`AND seats.version < :version`,
///     V008), and store the fact's version on success -- so a stale or redelivered fact is refused here
///     exactly as Postgres refuses it, and `findSeat` reports the stored version the caller needs to
///     tell an ordering refusal from a state refusal;
///   - `seats.event_id` is `NOT NULL REFERENCES events (id)`, so inserting a seat for an unknown event
///     fails here exactly as the foreign key would;
///   - `events.on_sale_at` is nullable, so it is stored and returned as a possibly-null `String` --
///     faithfully reproducing `row.getString(...)` handing back `null` for a SQL NULL, which is what
///     {@link EventStore.EventRow#onSaleAt()} exists to contain.
///
/// A previous copy of this fake overwrote seat state unconditionally in `markSeatSold`, which is why an
/// unguarded `UPDATE` survived a green test suite. Weakening any constraint here re-opens that hole.
public class InMemoryEventStore implements EventStore {
    /// One `events` row. `onSaleAt` is deliberately a nullable `String`: the column is nullable, and the
    /// point of several tests is that a SQL NULL is contained at the row boundary.
    private record StoredEvent(EventStatus status, String onSaleAt) {}

    /// One `seats` row, carrying the `event_id` foreign key the real table enforces and the `version`
    /// ordering column from V008 (0 for a seat that no fact has converged yet).
    private record StoredSeat(UUID eventId, SeatState state, long version) {}

    private final Map<UUID, StoredEvent> events = new HashMap<>();

    private final Map<UUID, StoredSeat> seats = new HashMap<>();

    /// Seed a seat directly in a given state, bypassing the guarded transitions. Models rows the guarded
    /// API cannot reach -- notably `withdrawn`, which the schema allows but no `@Query` produces. The
    /// seeded row starts at version 0, matching the column default. Fluent so a store can be built inline.
    public InMemoryEventStore withSeat(UUID id, UUID eventId, SeatState state) {
        seats.put(id, new StoredSeat(eventId, state, 0L));

        return this;
    }

    /// Current seat state, for assertions; empty when no such row exists.
    public Option<SeatState> seatStateOf(UUID id) {
        return Option.option(seats.get(id)).map(StoredSeat::state);
    }

    /// Current seat version, for assertions that a refused fact left the row untouched; empty when no
    /// such row exists.
    public Option<Long> seatVersionOf(UUID id) {
        return Option.option(seats.get(id)).map(StoredSeat::version);
    }

    /// Current event status, for assertions; empty when no such row exists.
    public Option<EventStatus> eventStatusOf(UUID id) {
        return Option.option(events.get(id)).map(StoredEvent::status);
    }

    @Override
    public Promise<Unit> insertEvent(UUID id, String venue, String onSaleAt) {
        events.put(id, new StoredEvent(EventStatus.DRAFT, onSaleAt));

        return Promise.UNIT;
    }

    /// Enforces `seats.event_id NOT NULL REFERENCES events (id)`: a seat for an unknown event is rejected
    /// here just as the foreign key rejects it in Postgres.
    @Override
    public Promise<Unit> insertSeat(UUID id, UUID eventId, String section, String seatRow, int number, String tier) {
        if (!events.containsKey(eventId)) {
            return Causes.cause("foreign key violation: seats.event_id -> events.id (" + eventId + ")").promise();
        }

        seats.put(id, new StoredSeat(eventId, SeatState.AVAILABLE, 0L));

        return Promise.UNIT;
    }

    @Override
    public Promise<Option<RowId>> openEvent(UUID id) {
        return transitionEvent(id, EventStatus.DRAFT, EventStatus.ON_SALE);
    }

    /// Mirrors `... AND status <> 'cancelled'`: the only guard in this store expressed as an exclusion.
    @Override
    public Promise<Option<RowId>> cancelEvent(UUID id) {
        return Promise.success(Option.option(events.get(id))
                                     .filter(event -> event.status() != EventStatus.CANCELLED)
                                     .map(event -> applyEventStatus(id, event, EventStatus.CANCELLED)));
    }

    @Override
    public Promise<Option<RowId>> blockSeat(UUID id) {
        return transitionSeat(id, SeatState.AVAILABLE, SeatState.BLOCKED);
    }

    @Override
    public Promise<Option<RowId>> releaseSeat(UUID id) {
        return transitionSeat(id, SeatState.BLOCKED, SeatState.AVAILABLE);
    }

    @Override
    public Promise<Option<EventRow>> findEvent(UUID id) {
        return Promise.success(Option.option(events.get(id)).map(event -> new EventRow(event.status(), event.onSaleAt())));
    }

    @Override
    public Promise<Option<SeatRow>> findSeat(UUID id) {
        return Promise.success(Option.option(seats.get(id)).map(seat -> new SeatRow(seat.state(), seat.version())));
    }

    @Override
    public Promise<Option<RowId>> markSeatSold(long version, UUID id) {
        return convergeSeat(id, SeatState.AVAILABLE, SeatState.SOLD, version);
    }

    @Override
    public Promise<Option<RowId>> markSeatAvailable(long version, UUID id) {
        return convergeSeat(id, SeatState.SOLD, SeatState.AVAILABLE, version);
    }

    private Promise<Option<RowId>> transitionEvent(UUID id, EventStatus from, EventStatus to) {
        return Promise.success(Option.option(events.get(id))
                                     .filter(event -> event.status() == from)
                                     .map(event -> applyEventStatus(id, event, to)));
    }

    private RowId applyEventStatus(UUID id, StoredEvent event, EventStatus to) {
        events.put(id, new StoredEvent(to, event.onSaleAt()));

        return new RowId(id);
    }

    /// An admin transition: state-guarded only, and it leaves `version` alone -- `blockSeat` and
    /// `releaseSeat` carry no fact version, so bumping the counter here would corrupt the per-seat
    /// sequence the booking subsystem owns.
    private Promise<Option<RowId>> transitionSeat(UUID id, SeatState from, SeatState to) {
        return Promise.success(Option.option(seats.get(id))
                                     .filter(seat -> seat.state() == from)
                                     .map(seat -> applySeatState(id,
                                                                 seat,
                                                                 to,
                                                                 seat.version())));
    }

    /// A fact-driven convergence: BOTH the state predicate and the ordering predicate must hold, and a
    /// successful transition stores the fact's version.
    private Promise<Option<RowId>> convergeSeat(UUID id, SeatState from, SeatState to, long version) {
        return Promise.success(Option.option(seats.get(id))
                                     .filter(seat -> seat.state() == from)
                                     .filter(seat -> seat.version() < version)
                                     .map(seat -> applySeatState(id, seat, to, version)));
    }

    private RowId applySeatState(UUID id, StoredSeat seat, SeatState to, long version) {
        seats.put(id, new StoredSeat(seat.eventId(), to, version));

        return new RowId(id);
    }
}
