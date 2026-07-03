namespace Ticketing.Availability.Projection

open System
open Pragmatica
open Aether

/// Persistence shared by the availability projection use cases (`project-seat-sold`,
/// `project-seat-released`): converge a seat's latest status in the `seat_availability` projection.
/// Single-statement, validator-friendly SQL only -- no CTEs (pg-codegen rc1 does not resolve
/// data-modifying CTE aliases). The upsert is idempotent on `seat_id`, so replaying a fact re-applies
/// the same terminal status (design-out convergence).
[<PgSql>]
type SeatProjectionStore =
    [<Query("INSERT INTO seat_availability (seat_id, event_id, state, updated_at) "
            + "VALUES (:seatId, :eventId, :state, now()) "
            + "ON CONFLICT (seat_id) DO UPDATE SET "
            + "state = EXCLUDED.state, event_id = EXCLUDED.event_id, updated_at = now()")>]
    abstract UpsertStatus: seatId: Guid * eventId: Guid * state: string -> Promise<unit>
