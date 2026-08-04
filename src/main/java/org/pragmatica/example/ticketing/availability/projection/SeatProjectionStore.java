package org.pragmatica.example.ticketing.availability.projection;

import java.util.UUID;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.example.ticketing.shared.SeatState;


/// Persistence shared by the availability projection use cases (`project-seat-sold`,
/// `project-seat-released`): converge a seat's latest status in the `seat_availability` projection.
/// Single-statement, validator-friendly SQL only -- no CTEs (pg-codegen rc3 does not resolve
/// data-modifying CTE aliases).
///
/// Seat status is **not terminal** -- a seat runs available -> sold -> available across a
/// cancellation -- so replay is not self-correcting and an idempotent-on-`seat_id` upsert is not
/// enough: the last fact to arrive would win regardless of the order the transitions actually
/// happened in. Each fact therefore carries `version`, the per-seat sequence from the reservation
/// slot (`reservations.version`, one row per seat, bumped by every lifecycle transition), and the
/// upsert applies only when the fact advances the row.
///
/// Guarantee earned, per seat: the projected status is the status of the highest-versioned fact ever
/// **delivered** for that seat, for any arrival order and any number of redeliveries. The mechanism
/// is the single-statement `ON CONFLICT ... WHERE seat_availability.version < EXCLUDED.version`
/// guard, which Postgres evaluates under the row lock the conflicting insert already holds -- the
/// same shape `PricingStore.upsertCurrent` uses.
///
/// NOT earned: completeness. Delivery is at-most-once under the current ephemeral pub-sub, so a fact
/// that is never delivered leaves the row permanently behind, and nothing in this statement detects
/// or repairs the gap -- the guard orders what arrives, it does not notice what does not.
@PgSql
public interface SeatProjectionStore {
    /// Ordered convergence of one seat. A fact whose `version` does not exceed the stored one is a
    /// redelivery or was overtaken by a later transition, and is discarded as a no-op.
    @Query("""
           INSERT INTO seat_availability (seat_id, event_id, state, version, updated_at)
           VALUES (:seatId, :eventId, :state, :version, now())
           ON CONFLICT (seat_id) DO UPDATE SET
           state = EXCLUDED.state, event_id = EXCLUDED.event_id, version = EXCLUDED.version,
           updated_at = now()
           WHERE seat_availability.version < EXCLUDED.version""")
    Promise<Unit> upsertStatus(UUID seatId, UUID eventId, SeatState state, long version);
}
