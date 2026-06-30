package org.pragmatica.example.ticketing.quote.projection.projectprice;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.UUID;


/// Write-side persistence for `project-price`: maintains the `price_view` projection from
/// `PriceChanged` facts. Single-statement, validator-friendly SQL only -- no CTEs. The upsert is
/// monotonic: a stale `PriceChanged` (lower version) is a no-op, so out-of-order delivery converges
/// (design-out).
@PgSql
public interface PriceProjectionStore {
    @Query("INSERT INTO price_view (scope_key, event_id, tier, amount_minor, currency, version, updated_at) "
          + "VALUES (:scopeKey, :eventId, :tier, :amountMinor, :currency, :version, now()) "
          + "ON CONFLICT (scope_key) DO UPDATE SET amount_minor = EXCLUDED.amount_minor, "
          + "currency = EXCLUDED.currency, version = EXCLUDED.version, updated_at = now() "
          + "WHERE price_view.version < EXCLUDED.version")
    Promise<Unit> upsertPrice(String scopeKey,
                              UUID eventId,
                              String tier,
                              long amountMinor,
                              String currency,
                              long version);
}
