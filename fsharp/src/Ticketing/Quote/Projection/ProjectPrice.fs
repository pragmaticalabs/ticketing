namespace Ticketing.Quote.Projection

open System
open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.Shared.Event

/// Write-side persistence for `project-price`: maintains the `price_view` projection from
/// `PriceChanged` facts. Single-statement, validator-friendly SQL only -- no CTEs. The upsert is
/// monotonic: a stale `PriceChanged` (lower version) is a no-op, so out-of-order delivery converges
/// (design-out).
[<PgSql>]
type PriceProjectionStore =
    [<Query("INSERT INTO price_view (scope_key, event_id, tier, amount_minor, currency, version, updated_at) "
            + "VALUES (:scopeKey, :eventId, :tier, :amountMinor, :currency, :version, now()) "
            + "ON CONFLICT (scope_key) DO UPDATE SET amount_minor = EXCLUDED.amount_minor, "
            + "currency = EXCLUDED.currency, version = EXCLUDED.version, updated_at = now() "
            + "WHERE price_view.version < EXCLUDED.version")>]
    abstract UpsertPrice: scopeKey: string * eventId: Guid * tier: string * amountMinor: int64 * currency: string *
                          version: int64 -> Promise<unit>

/// Use case: keep the customer-facing `price_view` projection fresh from `PriceChanged` facts.
/// Telescope leaf -- system `ticketing` -> subsystem `quote` -> workflow `projection` -> use case
/// `project-price`. Event consumer (no HTTP route); a malformed fact or a transient store error is
/// recovered to unit so the subscription never wedges (design-out: monotonic upsert converges).
[<Slice>]
module ProjectPrice =
    /// Java: `@PriceChangedSubscription` on execute -- the runtime wires the `price-changed` topic here.
    type Execute = PriceChanged -> Promise<unit>

    let projectPrice (store: PriceProjectionStore) : Execute =
        let project (id: EventId) (event: PriceChanged) : Promise<unit> =
            store.UpsertPrice($"{event.EventId}:{event.Tier}", EventId.value id, event.Tier, event.AmountMinor,
                              event.Currency, event.Version)

        fun event ->
            EventId.parse event.EventId
            |> Promise.fromResult
            |> Promise.bind (fun id -> project id event)
            |> Promise.recover ignore
