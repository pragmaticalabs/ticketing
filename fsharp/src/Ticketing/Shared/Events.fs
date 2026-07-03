/// Cross-subsystem facts and their pub-sub topics. In Java each topic has a pair of qualifier
/// annotations (`@SeatSoldPublisher` / `@SeatSoldSubscription`) that tell the runtime which topic
/// to wire; in F# the qualifier reduces to a named function type — a slice factory takes a
/// `SeatSoldPublisher`, a consumer slice's `Execute` IS the subscription signature.
namespace Ticketing.Shared.Event

open Aether

/// Published by booking when a seat is confirmed sold.
type SeatSold =
    { SeatId: string
      EventId: string
      BookingId: string }

/// Published when a held or booked seat returns to inventory.
type SeatReleased = { SeatId: string; EventId: string }

/// Published by pricing when a seat's price changes; version orders successive changes for a seat.
type PriceChanged =
    { EventId: string
      SeatId: string
      Tier: string
      AmountMinor: int64
      Currency: string
      Version: int64 }

/// Publisher for the `seat-sold` fact topic (Java: `@SeatSoldPublisher Publisher<SeatSold>`).
type SeatSoldPublisher = Publisher<SeatSold>

/// Publisher for the `seat-released` fact topic (Java: `@SeatReleasedPublisher Publisher<SeatReleased>`).
type SeatReleasedPublisher = Publisher<SeatReleased>

/// Publisher for the `price-changed` fact topic (Java: `@PriceChangedPublisher Publisher<PriceChanged>`).
type PriceChangedPublisher = Publisher<PriceChanged>
