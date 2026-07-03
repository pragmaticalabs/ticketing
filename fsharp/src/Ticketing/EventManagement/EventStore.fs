namespace Ticketing.EventManagement

open System
open Pragmatica
open Aether

/// Projection of a RETURNING id clause. Field order matches the RETURNING column order.
type RowId = { Id: Guid }

/// Current-state read of an event. Field order matches the SELECT column order.
type EventRow = { Status: string; OnSaleAt: string }

/// Event-management persistence (@PgSql), shared by every event-management use-case slice
/// (lifecycle, capacity, sales, convergence). Single-statement, validator-friendly SQL only -- no
/// CTEs (pg-codegen rc1 does not resolve data-modifying CTE aliases and mis-emits multi-line
/// literals). Lifecycle transitions are guarded `UPDATE ... RETURNING id`: an out-of-state
/// transition returns an empty projection (mapped to a typed failure in the slice) rather than
/// silently mutating a row. The `seat_row` column is mapped from the `seatRow` parameter (`row`
/// is a SQL keyword).
[<PgSql>]
type EventStore =
    [<Query("INSERT INTO events (id, venue, on_sale_at, status) VALUES (:id, :venue, :onSaleAt, 'draft')")>]
    abstract InsertEvent: id: Guid * venue: string * onSaleAt: string -> Promise<unit>

    [<Query("SELECT EXISTS(SELECT 1 FROM events WHERE id = :id)")>]
    abstract EventExists: id: Guid -> Promise<bool>

    [<Query("INSERT INTO seats (id, event_id, section, seat_row, number, tier, state) "
            + "VALUES (:id, :eventId, :section, :seatRow, :number, :tier, 'available')")>]
    abstract InsertSeat: id: Guid * eventId: Guid * section: string * seatRow: string * number: int * tier: string
                             -> Promise<unit>

    [<Query("UPDATE events SET status = 'on_sale' WHERE id = :id AND status = 'draft' RETURNING id")>]
    abstract OpenEvent: id: Guid -> Promise<RowId option>

    [<Query("UPDATE events SET status = 'cancelled' WHERE id = :id RETURNING id")>]
    abstract CancelEvent: id: Guid -> Promise<RowId option>

    [<Query("UPDATE seats SET state = 'blocked' WHERE id = :id AND state = 'available' RETURNING id")>]
    abstract BlockSeat: id: Guid -> Promise<RowId option>

    [<Query("UPDATE seats SET state = 'available' WHERE id = :id AND state = 'blocked' RETURNING id")>]
    abstract ReleaseSeat: id: Guid -> Promise<RowId option>

    [<Query("SELECT status, on_sale_at FROM events WHERE id = :id")>]
    abstract FindEvent: id: Guid -> Promise<EventRow option>

    [<Query("UPDATE seats SET state = 'sold' WHERE id = :id")>]
    abstract MarkSeatSold: id: Guid -> Promise<unit>

    [<Query("UPDATE seats SET state = 'available' WHERE id = :id AND state = 'sold'")>]
    abstract MarkSeatAvailable: id: Guid -> Promise<unit>
