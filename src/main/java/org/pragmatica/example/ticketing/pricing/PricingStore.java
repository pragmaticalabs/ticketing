package org.pragmatica.example.ticketing.pricing;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.UUID;


/// Pricing-subsystem persistence, shared by every pricing use-case slice: an append-only
/// `price_events` log (design-out) plus a `current_price` projection. Single-statement,
/// validator-friendly SQL only.
@PgSql
public interface PricingStore {
    /// Per-process projection row; component order matches the SELECT column order.
    @SuppressWarnings("JBCT-VO-01")
    record PriceRow(long amountMinor, String currency, String tier, long version) {}

    /// Append a new price-history row, allocating its version atomically in the same statement as
    /// `max(existing version) + 1` for this (event, tier), and return it. A `UNIQUE (event_id, tier,
    /// version)` index makes a concurrent double-allocation impossible: the loser fails the insert
    /// (a visible error) rather than silently corrupting history. Single statement, no CTE.
    @Query("INSERT INTO price_events (id, event_id, tier, amount_minor, currency, version) "
          + "SELECT :id, :eventId, :tier, :amountMinor, :currency, coalesce(max(pe.version), 0) + 1 "
          + "FROM price_events pe WHERE pe.event_id = :eventId AND pe.tier = :tier "
          + "RETURNING version")
    Promise<Long> appendPrice(UUID id, UUID eventId, String tier, long amountMinor, String currency);

    @Query("INSERT INTO current_price (scope_key, event_id, tier, amount_minor, currency, version, updated_at) "
          + "VALUES (:scopeKey, :eventId, :tier, :amountMinor, :currency, :version, now()) "
          + "ON CONFLICT (scope_key) DO UPDATE SET amount_minor = EXCLUDED.amount_minor, "
          + "currency = EXCLUDED.currency, version = EXCLUDED.version, updated_at = now() "
          + "WHERE current_price.version < EXCLUDED.version")
    Promise<Unit> upsertCurrent(String scopeKey,
                                UUID eventId,
                                String tier,
                                long amountMinor,
                                String currency,
                                long version);

    @Query("SELECT amount_minor, currency, tier, version FROM current_price WHERE scope_key = :scopeKey")
    Promise<Option<PriceRow>> findCurrent(String scopeKey);
}
