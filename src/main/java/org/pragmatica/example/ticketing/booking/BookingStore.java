package org.pragmatica.example.ticketing.booking;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.UUID;


/// Booking persistence (@PgSql), shared by every booking use-case slice. Single-statement,
/// validator-friendly SQL only -- no CTEs (pg-codegen rc1 does not resolve data-modifying CTE
/// aliases and mis-emits multi-line literals). The seat claim is the design-out serialization
/// point: one reservation row per seat, reclaimed atomically by an `INSERT ... ON CONFLICT
/// (seat_id) DO UPDATE ... WHERE` guard, so a fresh hold or confirmed booking can never be
/// overwritten (the losing buyer sees zero rows -> typed SeatUnavailable in the slice). Lifecycle
/// transitions are guarded `UPDATE ... RETURNING id`: an out-of-state row yields an empty
/// projection rather than silently mutating. Sale status and price are now read synchronously from
/// the event-management and pricing slices, so this store keeps no local read projections.
@PgSql
public interface BookingStore {
    /// Projection of a RETURNING id clause. Component order matches the RETURNING column order.
    @SuppressWarnings("JBCT-VO-01")
    record RowId(UUID id) {}

    /// A freed seat from the expiry sweep. Component order matches the RETURNING column order.
    @SuppressWarnings("JBCT-VO-01")
    record SeatRef(UUID seatId, UUID eventId) {}

    /// Current-state read of a booking. Component order matches the SELECT column order.
    @SuppressWarnings("JBCT-VO-01")
    record BookingRow(String status, UUID customerId, UUID seatId, UUID ticketId, UUID eventId) {}

    /// Hold decay snapshot: the persisted state plus time-as-decay flags computed at read.
    /// Component order matches the SELECT column order.
    @SuppressWarnings("JBCT-VO-01")
    record HoldRow(String state, boolean expired, boolean stale) {}

    @Query("INSERT INTO reservations (id, seat_id, event_id, customer_id, state, expires_at, created_at) "
          + "VALUES (:id, :seatId, :eventId, :customerId, 'held', now() + interval '15 minutes', now()) "
          + "ON CONFLICT (seat_id) DO UPDATE SET id = EXCLUDED.id, event_id = EXCLUDED.event_id, "
          + "customer_id = EXCLUDED.customer_id, state = 'held', expires_at = EXCLUDED.expires_at, "
          + "created_at = now() "
          + "WHERE reservations.state IN ('cancelled', 'expired') "
          + "OR (reservations.state = 'held' AND reservations.expires_at < now()) "
          + "RETURNING id")
    Promise<Option<RowId>> claimSeat(UUID id, UUID seatId, UUID eventId, UUID customerId);

    @Query("UPDATE reservations SET state = 'confirmed', expires_at = NULL "
          + "WHERE id = :id AND state = 'held' RETURNING id")
    Promise<Option<RowId>> confirmReservation(UUID id);

    @Query("UPDATE reservations SET state = 'cancelled' WHERE id = :id RETURNING id")
    Promise<Option<RowId>> releaseReservation(UUID id);

    @Query("UPDATE reservations SET state = 'cancelled' "
          + "WHERE seat_id = :seatId AND state = 'confirmed' RETURNING id")
    Promise<Option<RowId>> cancelReservationBySeat(UUID seatId);

    @Query("SELECT count(*) FROM bookings WHERE customer_id = :customerId AND status = 'confirmed'")
    Promise<Long> activeBookingCount(UUID customerId);

    @Query("INSERT INTO bookings (id, reservation_id, seat_id, event_id, customer_id, status, ticket_id) "
          + "VALUES (:id, :reservationId, :seatId, :eventId, :customerId, 'confirmed', :ticketId)")
    Promise<Unit> insertBooking(UUID id,
                                UUID reservationId,
                                UUID seatId,
                                UUID eventId,
                                UUID customerId,
                                UUID ticketId);

    @Query("INSERT INTO payments (id, booking_id, status, receipt_id, amount_minor, currency) "
          + "VALUES (:id, :bookingId, :status, :receiptId, :amountMinor, :currency)")
    Promise<Unit> insertPayment(UUID id,
                                UUID bookingId,
                                String status,
                                UUID receiptId,
                                long amountMinor,
                                String currency);

    @Query("INSERT INTO tickets (id, booking_id, seat_id, status) " + "VALUES (:id, :bookingId, :seatId, 'issued')")
    Promise<Unit> insertTicket(UUID id, UUID bookingId, UUID seatId);

    @Query("SELECT status, customer_id, seat_id, ticket_id, event_id FROM bookings WHERE id = :id")
    Promise<Option<BookingRow>> findBooking(UUID id);

    @Query("UPDATE bookings SET status = 'cancelled' WHERE id = :id AND status = 'confirmed' RETURNING id")
    Promise<Option<RowId>> cancelBooking(UUID id);

    @Query("UPDATE tickets SET status = 'invalidated' WHERE id = :id")
    Promise<Unit> invalidateTicket(UUID id);

    @Query("SELECT state, (expires_at < now()) AS expired, "
          + "(expires_at < now() + interval '5 minutes') AS stale "
          + "FROM reservations WHERE seat_id = :seatId")
    Promise<Option<HoldRow>> holdDecay(UUID seatId);

    @Query("UPDATE reservations SET state = 'expired' "
          + "WHERE state = 'held' AND expires_at < now() RETURNING seat_id, event_id")
    Promise<List<SeatRef>> expireHolds();
}
