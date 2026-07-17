package org.pragmatica.example.ticketing.availability.query.seatstatus;

import java.util.UUID;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.shared.SeatState;


/// Persistence for the `seat-status` query use case: read a single seat's latest status from the
/// `seat_availability` projection. Single-statement, validator-friendly SQL only. A seat with no
/// projection row was never sold/held, so `findStatus` reads as empty (the slice defaults it to
/// available).
@PgSql
public interface SeatStatusStore {
    /// Per-process projection row. Component order matches the SELECT column order. The `state` column
    /// decodes to `SeatState` via its `valueMapping()` (parse-don't-validate at the row boundary).
    record StatusRow(SeatState state) {}

    @Query("SELECT state FROM seat_availability WHERE seat_id = :seatId")
    Promise<Option<StatusRow>> findStatus(UUID seatId);
}
