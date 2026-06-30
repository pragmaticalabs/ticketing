package org.pragmatica.example.ticketing.availability.projection;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.UUID;


/// Persistence shared by the availability projection use cases (`project-seat-sold`,
/// `project-seat-released`): converge a seat's latest status in the `seat_availability` projection.
/// Single-statement, validator-friendly SQL only -- no CTEs (pg-codegen rc1 does not resolve
/// data-modifying CTE aliases). The upsert is idempotent on `seat_id`, so replaying a fact re-applies
/// the same terminal status (design-out convergence).
@PgSql
public interface SeatProjectionStore {
    @Query("INSERT INTO seat_availability (seat_id, event_id, state, updated_at) "
          + "VALUES (:seatId, :eventId, :state, now()) "
          + "ON CONFLICT (seat_id) DO UPDATE SET "
          + "state = EXCLUDED.state, event_id = EXCLUDED.event_id, updated_at = now()")
    Promise<Unit> upsertStatus(UUID seatId, UUID eventId, String state);
}
