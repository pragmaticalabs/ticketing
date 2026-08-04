package org.pragmatica.example.ticketing.eventmanagement;

import java.util.UUID;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.example.ticketing.shared.SeatState;

import static org.pragmatica.lang.Option.option;


/// Event-management persistence (@PgSql), shared by every event-management use-case slice
/// (lifecycle, capacity, sales, convergence). Single-statement, validator-friendly SQL only -- no
/// CTEs (pg-codegen does not resolve data-modifying CTE aliases). The `seat_row` column is mapped
/// from the `seatRow` record field (`row` is a SQL keyword).
///
/// **Every** state transition here is a guarded `UPDATE ... WHERE <key> AND <current state> RETURNING
/// id`: an out-of-state transition matches no row and returns an empty projection, which the calling
/// slice turns into a typed outcome, rather than silently overwriting a row. That uniformity is the
/// invariant -- an unguarded transition in this interface is a defect, because a `Promise<Unit>` write
/// cannot express "matched nothing" and therefore reports a no-op as success.
///
/// The guard is authoritative but not self-describing: it says *that* it refused, never *why*. A slice
/// that needs the reason issues a follow-up `findEvent`/`findSeat` read, which is a separate statement
/// and therefore a best-effort diagnosis, not part of the transition's atomicity.
@PgSql
public interface EventStore {
    /// Projection of a RETURNING id clause. Component order matches the RETURNING column order.
    record RowId(UUID id) {}

    /// Current-state read of an event. The `status` column decodes to [EventStatus] via its
    /// `valueMapping()` (parse-don't-validate at the row boundary).
    record EventRow(EventStatus status, String rawOnSaleAt) {
        /// `events.on_sale_at` is a NULLABLE column, and pg-codegen rejects an `Option<T>` record
        /// component, so the raw column necessarily arrives as a possibly-null `String`. This accessor
        /// is the sanctioned way to read it: it applies the JBCT adapter-boundary idiom
        /// `Option.option(nullable)` so a SQL NULL becomes an empty `Option` here, at the edge, instead
        /// of leaking a Java null onward into a slice response.
        public Option<String> onSaleAt() {
            return option(rawOnSaleAt);
        }
    }

    /// Current-state read of a seat. The `state` column decodes to [SeatState] via its `valueMapping()`
    /// (parse-don't-validate at the row boundary). Used to diagnose a refused seat transition: `version`
    /// says whether the seat is already at or beyond the refusing fact's position (the fact is settled)
    /// or still behind it (the state predicate is what refused). Component order matches the SELECT
    /// column order.
    record SeatRow(SeatState state, long version) {}

    @Query("INSERT INTO events (id, venue, on_sale_at, status) VALUES (:id, :venue, :onSaleAt, 'draft')")
    Promise<Unit> insertEvent(UUID id, String venue, String onSaleAt);

    @Query("""
           INSERT INTO seats (id, event_id, section, seat_row, number, tier, state)
           VALUES (:id, :eventId, :section, :seatRow, :number, :tier, 'available')""")
    Promise<Unit> insertSeat(UUID id, UUID eventId, String section, String seatRow, int number, String tier);

    @Query("UPDATE events SET status = 'on_sale' WHERE id = :id AND status = 'draft' RETURNING id")
    Promise<Option<RowId>> openEvent(UUID id);

    /// Guarded withdrawal: only a not-yet-cancelled event transitions, so a repeat cancel matches no row
    /// and is reported as an empty projection instead of re-stamping a terminal row.
    @Query("UPDATE events SET status = 'cancelled' WHERE id = :id AND status <> 'cancelled' RETURNING id")
    Promise<Option<RowId>> cancelEvent(UUID id);

    @Query("UPDATE seats SET state = 'blocked' WHERE id = :id AND state = 'available' RETURNING id")
    Promise<Option<RowId>> blockSeat(UUID id);

    @Query("UPDATE seats SET state = 'available' WHERE id = :id AND state = 'blocked' RETURNING id")
    Promise<Option<RowId>> releaseSeat(UUID id);

    /// `on_sale_at` is aliased so it binds to the deliberately-raw [EventRow] component; read it through
    /// `EventRow.onSaleAt()`, which wraps the nullable column in an `Option`.
    @Query("SELECT status, on_sale_at AS raw_on_sale_at FROM events WHERE id = :id")
    Promise<Option<EventRow>> findEvent(UUID id);

    @Query("SELECT state, version FROM seats WHERE id = :id")
    Promise<Option<SeatRow>> findSeat(UUID id);

    /// Guarded convergence to sold, on **two** independent predicates that both must hold.
    ///
    ///   - `state = 'available'` -- authority: only a seat genuinely on sale may be sold, so this can
    ///     never overwrite a `blocked` or `withdrawn` seat;
    ///   - `seats.version < :version` -- ordering: the fact must advance the seat's per-seat sequence
    ///     (`reservations.version`, carried on the fact), so a redelivered or overtaken fact cannot move
    ///     seat state backwards. Adding this did not relax the state predicate; both apply.
    ///
    /// Either predicate refusing matches no row and yields an empty projection. The caller tells the two
    /// refusals apart by reading [#findSeat]: a stored `version` at or beyond `:version` means the fact
    /// was settled (redelivery or overtaken); a lower one means the state predicate refused, which is a
    /// real divergence.
    ///
    /// `version` precedes `id` in the parameter list because pg-codegen binds parameters positionally in
    /// order of first appearance of `:name` in the SQL, and an `UPDATE`'s `SET` clause necessarily comes
    /// before its `WHERE`. Same reason as [#markRefunded] on the booking side; reordering the signature
    /// to read more naturally makes the generated implementation stop overriding this method.
    @Query("""
           UPDATE seats SET state = 'sold', version = :version
           WHERE id = :id AND state = 'available' AND seats.version < :version RETURNING id""")
    Promise<Option<RowId>> markSeatSold(long version, UUID id);

    /// Guarded convergence back to available; mirrors [#markSeatSold] exactly, including both predicates,
    /// the two refusal cases and the parameter order.
    @Query("""
           UPDATE seats SET state = 'available', version = :version
           WHERE id = :id AND state = 'sold' AND seats.version < :version RETURNING id""")
    Promise<Option<RowId>> markSeatAvailable(long version, UUID id);
}
