package org.pragmatica.example.ticketing.availability.query.soldcount;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Promise;

import java.util.UUID;


/// Persistence for the `sold-count` query use case: count the sold seats of an event from the
/// `seat_availability` projection. Single-statement, validator-friendly SQL only.
@PgSql
public interface SoldCountStore {
    @Query("SELECT count(*) FROM seat_availability WHERE event_id = :eventId AND state = 'sold'")
    Promise<Long> countSold(UUID eventId);
}
