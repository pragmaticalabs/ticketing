namespace Ticketing.Booking

open System
open Pragmatica
open Aether

/// Projection of a RETURNING id clause. Field order matches the RETURNING column order.
type RowId = { Id: Guid }

/// A freed seat from the expiry sweep. Field order matches the RETURNING column order.
type SeatRef = { SeatId: Guid; EventId: Guid }

/// Current-state read of a booking. Field order matches the SELECT column order.
type BookingRow =
    { Status: string
      CustomerId: Guid
      SeatId: Guid
      TicketId: Guid
      EventId: Guid }

/// Hold decay snapshot: the persisted state plus time-as-decay flags computed at read.
/// Field order matches the SELECT column order.
type HoldRow =
    { State: string
      Expired: bool
      Stale: bool }

/// Booking persistence (@PgSql), shared by every booking use-case slice. Single-statement,
/// validator-friendly SQL only -- no CTEs (pg-codegen rc1 does not resolve data-modifying CTE
/// aliases and mis-emits multi-line literals). The seat claim is the design-out serialization
/// point: one reservation row per seat, reclaimed atomically by an `INSERT ... ON CONFLICT
/// (seat_id) DO UPDATE ... WHERE` guard, so a fresh hold or confirmed booking can never be
/// overwritten (the losing buyer sees zero rows -> typed SeatUnavailable in the slice). Lifecycle
/// transitions are guarded `UPDATE ... RETURNING id`: an out-of-state row yields an empty
/// projection rather than silently mutating. Sale status and price are read synchronously from
/// the event-management and pricing slices, so this store keeps no local read projections.
[<PgSql>]
type BookingStore =
    [<Query("INSERT INTO reservations (id, seat_id, event_id, customer_id, state, expires_at, created_at) "
            + "VALUES (:id, :seatId, :eventId, :customerId, 'held', now() + interval '15 minutes', now()) "
            + "ON CONFLICT (seat_id) DO UPDATE SET id = EXCLUDED.id, event_id = EXCLUDED.event_id, "
            + "customer_id = EXCLUDED.customer_id, state = 'held', expires_at = EXCLUDED.expires_at, "
            + "created_at = now() "
            + "WHERE reservations.state IN ('cancelled', 'expired') "
            + "OR (reservations.state = 'held' AND reservations.expires_at < now()) "
            + "RETURNING id")>]
    abstract ClaimSeat: id: Guid * seatId: Guid * eventId: Guid * customerId: Guid -> Promise<RowId option>

    [<Query("UPDATE reservations SET state = 'confirmed', expires_at = NULL "
            + "WHERE id = :id AND state = 'held' RETURNING id")>]
    abstract ConfirmReservation: id: Guid -> Promise<RowId option>

    [<Query("UPDATE reservations SET state = 'cancelled' WHERE id = :id RETURNING id")>]
    abstract ReleaseReservation: id: Guid -> Promise<RowId option>

    [<Query("UPDATE reservations SET state = 'cancelled' "
            + "WHERE seat_id = :seatId AND state = 'confirmed' RETURNING id")>]
    abstract CancelReservationBySeat: seatId: Guid -> Promise<RowId option>

    [<Query("SELECT count(*) FROM bookings WHERE customer_id = :customerId AND status = 'confirmed'")>]
    abstract ActiveBookingCount: customerId: Guid -> Promise<int64>

    [<Query("INSERT INTO bookings (id, reservation_id, seat_id, event_id, customer_id, status, ticket_id) "
            + "VALUES (:id, :reservationId, :seatId, :eventId, :customerId, 'confirmed', :ticketId)")>]
    abstract InsertBooking: id: Guid * reservationId: Guid * seatId: Guid * eventId: Guid * customerId: Guid *
                            ticketId: Guid -> Promise<unit>

    [<Query("INSERT INTO payments (id, booking_id, status, receipt_id, amount_minor, currency) "
            + "VALUES (:id, :bookingId, :status, :receiptId, :amountMinor, :currency)")>]
    abstract InsertPayment: id: Guid * bookingId: Guid * status: string * receiptId: Guid * amountMinor: int64 *
                            currency: string -> Promise<unit>

    [<Query("INSERT INTO tickets (id, booking_id, seat_id, status) VALUES (:id, :bookingId, :seatId, 'issued')")>]
    abstract InsertTicket: id: Guid * bookingId: Guid * seatId: Guid -> Promise<unit>

    [<Query("SELECT status, customer_id, seat_id, ticket_id, event_id FROM bookings WHERE id = :id")>]
    abstract FindBooking: id: Guid -> Promise<BookingRow option>

    [<Query("UPDATE bookings SET status = 'cancelled' WHERE id = :id AND status = 'confirmed' RETURNING id")>]
    abstract CancelBooking: id: Guid -> Promise<RowId option>

    [<Query("UPDATE tickets SET status = 'invalidated' WHERE id = :id")>]
    abstract InvalidateTicket: id: Guid -> Promise<unit>

    [<Query("SELECT state, (expires_at < now()) AS expired, "
            + "(expires_at < now() + interval '5 minutes') AS stale "
            + "FROM reservations WHERE seat_id = :seatId")>]
    abstract HoldDecay: seatId: Guid -> Promise<HoldRow option>

    [<Query("UPDATE reservations SET state = 'expired' "
            + "WHERE state = 'held' AND expires_at < now() RETURNING seat_id, event_id")>]
    abstract ExpireHolds: unit -> Promise<SeatRef list>
