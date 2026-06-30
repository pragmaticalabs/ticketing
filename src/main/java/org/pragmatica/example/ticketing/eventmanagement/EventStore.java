package org.pragmatica.example.ticketing.eventmanagement;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.UUID;


/// Event-management persistence (@PgSql), shared by every event-management use-case slice
/// (lifecycle, capacity, sales, convergence). Single-statement, validator-friendly SQL only -- no
/// CTEs (pg-codegen rc1 does not resolve data-modifying CTE aliases and mis-emits multi-line
/// literals). Lifecycle transitions are guarded `UPDATE ... RETURNING id`: an out-of-state
/// transition returns an empty projection (mapped to a typed failure in the slice) rather than
/// silently mutating a row. The `seat_row` column is mapped from the `seatRow` record field (`row`
/// is a SQL keyword).
@PgSql
public interface EventStore {
    /// Projection of a RETURNING id clause. Component order matches the RETURNING column order.
    @SuppressWarnings("JBCT-VO-01")
    record RowId(UUID id) {}

    /// Current-state read of an event. Component order matches the SELECT column order.
    @SuppressWarnings("JBCT-VO-01")
    record EventRow(String status, String onSaleAt) {}

    @Query("INSERT INTO events (id, venue, on_sale_at, status) VALUES (:id, :venue, :onSaleAt, 'draft')")
    Promise<Unit> insertEvent(UUID id, String venue, String onSaleAt);

    @Query("SELECT EXISTS(SELECT 1 FROM events WHERE id = :id)")
    Promise<Boolean> eventExists(UUID id);

    @Query("INSERT INTO seats (id, event_id, section, seat_row, number, tier, state) "
          + "VALUES (:id, :eventId, :section, :seatRow, :number, :tier, 'available')")
    Promise<Unit> insertSeat(UUID id, UUID eventId, String section, String seatRow, int number, String tier);

    @Query("UPDATE events SET status = 'on_sale' WHERE id = :id AND status = 'draft' RETURNING id")
    Promise<Option<RowId>> openEvent(UUID id);

    @Query("UPDATE events SET status = 'cancelled' WHERE id = :id RETURNING id")
    Promise<Option<RowId>> cancelEvent(UUID id);

    @Query("UPDATE seats SET state = 'blocked' WHERE id = :id AND state = 'available' RETURNING id")
    Promise<Option<RowId>> blockSeat(UUID id);

    @Query("UPDATE seats SET state = 'available' WHERE id = :id AND state = 'blocked' RETURNING id")
    Promise<Option<RowId>> releaseSeat(UUID id);

    @Query("SELECT status, on_sale_at FROM events WHERE id = :id")
    Promise<Option<EventRow>> findEvent(UUID id);

    @Query("UPDATE seats SET state = 'sold' WHERE id = :id")
    Promise<Unit> markSeatSold(UUID id);

    @Query("UPDATE seats SET state = 'available' WHERE id = :id AND state = 'sold'")
    Promise<Unit> markSeatAvailable(UUID id);
}
