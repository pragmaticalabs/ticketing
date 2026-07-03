namespace Ticketing.Pricing

open System
open Pragmatica
open Aether

/// Per-process projection row; field order matches the SELECT column order.
type PriceRow =
    { AmountMinor: int64
      Currency: string
      Tier: string
      Version: int64 }

/// Pricing-subsystem persistence, shared by every pricing use-case slice: an append-only
/// `price_events` log (design-out) plus a `current_price` projection. Single-statement,
/// validator-friendly SQL only.
[<PgSql>]
type PricingStore =
    /// Append a new price-history row, allocating its version atomically in the same statement as
    /// `max(existing version) + 1` for this (event, tier), and return it. A `UNIQUE (event_id, tier,
    /// version)` index makes a concurrent double-allocation impossible: the loser fails the insert
    /// (a visible error) rather than silently corrupting history. Single statement, no CTE.
    [<Query("INSERT INTO price_events (id, event_id, tier, amount_minor, currency, version) "
            + "SELECT :id, :eventId, :tier, :amountMinor, :currency, coalesce(max(pe.version), 0) + 1 "
            + "FROM price_events pe WHERE pe.event_id = :eventId AND pe.tier = :tier "
            + "RETURNING version")>]
    abstract AppendPrice: id: Guid * eventId: Guid * tier: string * amountMinor: int64 * currency: string
                              -> Promise<int64>

    [<Query("INSERT INTO current_price (scope_key, event_id, tier, amount_minor, currency, version, updated_at) "
            + "VALUES (:scopeKey, :eventId, :tier, :amountMinor, :currency, :version, now()) "
            + "ON CONFLICT (scope_key) DO UPDATE SET amount_minor = EXCLUDED.amount_minor, "
            + "currency = EXCLUDED.currency, version = EXCLUDED.version, updated_at = now() "
            + "WHERE current_price.version < EXCLUDED.version")>]
    abstract UpsertCurrent: scopeKey: string * eventId: Guid * tier: string * amountMinor: int64 * currency: string *
                            version: int64 -> Promise<unit>

    [<Query("SELECT amount_minor, currency, tier, version FROM current_price WHERE scope_key = :scopeKey")>]
    abstract FindCurrent: scopeKey: string -> Promise<PriceRow option>
