package org.pragmatica.example.ticketing.availability.query.seatstatus;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.UUID;


/// Persistence for the `seat-status` query use case: read a single seat's latest status from the
/// `seat_availability` projection. Single-statement, validator-friendly SQL only. A seat with no
/// projection row was never sold/held, so `findStatus` reads as empty (the slice defaults it to
/// available).
@PgSql
public interface SeatStatusStore {
    /// Per-process projection row. Component order matches the SELECT column order.
    @SuppressWarnings("JBCT-VO-01")
    record StatusRow(String state) {}

    @Query("SELECT state FROM seat_availability WHERE seat_id = :seatId")
    Promise<Option<StatusRow>> findStatus(UUID seatId);
}
