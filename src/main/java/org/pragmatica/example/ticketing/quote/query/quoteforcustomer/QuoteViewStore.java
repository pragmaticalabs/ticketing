package org.pragmatica.example.ticketing.quote.query.quoteforcustomer;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// Read-side persistence for `quote-for-customer`: looks up the latest projected price for a
/// `scope_key` (event:tier) in the `price_view` projection. One use case, one store, one query.
@PgSql
public interface QuoteViewStore {
    /// Per-process projection row; component order matches the SELECT column order.
    @SuppressWarnings("JBCT-VO-01")
    record PriceRow(long amountMinor, String currency, String tier, long version) {}

    @Query("SELECT amount_minor, currency, tier, version FROM price_view WHERE scope_key = :scopeKey")
    Promise<Option<PriceRow>> findByScope(String scopeKey);
}
