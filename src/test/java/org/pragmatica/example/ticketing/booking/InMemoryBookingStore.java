package org.pragmatica.example.ticketing.booking;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


/// Shared in-memory fake of the @PgSql {@link BookingStore}, used by every booking slice test. The
/// seat claim refuses while a held/confirmed reservation is active (design-out), and lifecycle
/// updates are guarded exactly like the SQL `... WHERE state = ...`. Public (and non-final so the
/// failure-injecting {@link FailingBookingStore} can subclass it) so the deep-package slice tests can
/// reuse it.
///
/// The real SQL derives the `expired`/`stale` decay flags from `expires_at` relative to `now()`; this
/// fake has no clock, so hold decay is an injectable property (default {@link Decay#FRESH}) set via
/// {@link #withDecay(Decay)} -- letting a test deterministically read back a FRESH, STALE or EXPIRED
/// hold without sleeping.
public class InMemoryBookingStore implements BookingStore {
    /// Injectable hold-decay state, mapping a label to the SQL-equivalent (expired, stale) flag pair.
    /// EXPIRED is also stale, matching `expires_at < now()` implying `expires_at < now() + 5 minutes`.
    public enum Decay {
        FRESH(false, false),
        STALE(false, true),
        EXPIRED(true, true);

        private final boolean expired;
        private final boolean stale;

        Decay(boolean expired, boolean stale) {
            this.expired = expired;
            this.stale = stale;
        }

        boolean expired() {
            return expired;
        }

        boolean stale() {
            return stale;
        }
    }

    private record StoredReservation(UUID id, UUID seatId, UUID eventId, UUID customerId, String state) {}

    private record StoredBooking(UUID id, UUID seatId, UUID eventId, UUID customerId, String status, UUID ticketId) {}

    private final Map<UUID, StoredReservation> reservationsBySeat = new HashMap<>();
    private final Map<UUID, StoredBooking> bookings = new HashMap<>();
    private final Map<UUID, String> tickets = new HashMap<>();
    private final Map<UUID, String> payments = new HashMap<>();
    private Decay decay = Decay.FRESH;

    /// Set the decay state reported by {@link #holdDecay(UUID)}; fluent so a store can be built inline.
    public InMemoryBookingStore withDecay(Decay decay) {
        this.decay = decay;
        return this;
    }

    @Override
    public Promise<Option<RowId>> claimSeat(UUID id, UUID seatId, UUID eventId, UUID customerId) {
        var existing = reservationsBySeat.get(seatId);

        if (existing != null && (existing.state().equals("held") || existing.state().equals("confirmed"))) {
            return Promise.success(Option.empty());
        }

        reservationsBySeat.put(seatId, new StoredReservation(id, seatId, eventId, customerId, "held"));

        return Promise.success(Option.present(new RowId(id)));
    }

    @Override
    public Promise<Option<RowId>> confirmReservation(UUID id) {
        return transitionReservationById(id, "held", "confirmed");
    }

    @Override
    public Promise<Option<RowId>> releaseReservation(UUID id) {
        return forceReservationById(id, "cancelled");
    }

    @Override
    public Promise<Option<RowId>> cancelReservationBySeat(UUID seatId) {
        var existing = reservationsBySeat.get(seatId);

        if (existing == null || !existing.state().equals("confirmed")) {
            return Promise.success(Option.empty());
        }

        reservationsBySeat.put(seatId, withState(existing, "cancelled"));

        return Promise.success(Option.present(new RowId(existing.id())));
    }

    @Override
    public Promise<Long> activeBookingCount(UUID customerId) {
        return Promise.success(bookings.values()
                                       .stream()
                                       .filter(booking -> booking.customerId()
                                                                 .equals(customerId))
                                       .filter(booking -> booking.status()
                                                                 .equals("confirmed"))
                                       .count());
    }

    @Override
    public Promise<Unit> insertBooking(UUID id,
                                       UUID reservationId,
                                       UUID seatId,
                                       UUID eventId,
                                       UUID customerId,
                                       UUID ticketId) {
        bookings.put(id, new StoredBooking(id, seatId, eventId, customerId, "confirmed", ticketId));

        return Promise.UNIT;
    }

    @Override
    public Promise<Unit> insertPayment(UUID id,
                                       UUID bookingId,
                                       String status,
                                       UUID receiptId,
                                       long amountMinor,
                                       String currency) {
        payments.put(id, status);

        return Promise.UNIT;
    }

    @Override
    public Promise<Unit> insertTicket(UUID id, UUID bookingId, UUID seatId) {
        tickets.put(id, "issued");

        return Promise.UNIT;
    }

    @Override
    public Promise<Option<BookingRow>> findBooking(UUID id) {
        return Promise.success(Option.option(bookings.get(id)).map(booking -> new BookingRow(booking.status(),
                                                                                             booking.customerId(),
                                                                                             booking.seatId(),
                                                                                             booking.ticketId(),
                                                                                             booking.eventId())));
    }

    @Override
    public Promise<Option<RowId>> cancelBooking(UUID id) {
        var existing = bookings.get(id);

        if (existing == null || !existing.status().equals("confirmed")) {
            return Promise.success(Option.empty());
        }

        bookings.put(id, withStatus(existing, "cancelled"));

        return Promise.success(Option.present(new RowId(id)));
    }

    @Override
    public Promise<Unit> invalidateTicket(UUID id) {
        tickets.computeIfPresent(id, (_, _) -> "invalidated");

        return Promise.UNIT;
    }

    @Override
    public Promise<Option<HoldRow>> holdDecay(UUID seatId) {
        return Promise.success(Option.option(reservationsBySeat.get(seatId)).map(reservation -> new HoldRow(reservation.state(),
                                                                                                            decay.expired(),
                                                                                                            decay.stale())));
    }

    @Override
    public Promise<List<SeatRef>> expireHolds() {
        var freed = new ArrayList<SeatRef>();

        reservationsBySeat.values().stream().filter(reservation -> reservation.state()
                                                                              .equals("held")).forEach(reservation -> freed.add(new SeatRef(reservation.seatId(),
                                                                                                                                            reservation.eventId())));
        freed.forEach(seat -> reservationsBySeat.put(seat.seatId(),
                                                     withState(reservationsBySeat.get(seat.seatId()),
                                                               "expired")));

        return Promise.success(List.copyOf(freed));
    }

    public String reservationStateBySeat(UUID seatId) {
        return reservationsBySeat.get(seatId)
                                 .state();
    }

    private Promise<Option<RowId>> transitionReservationById(UUID id, String from, String to) {
        return reservationsBySeat.entrySet()
                                 .stream()
                                 .filter(entry -> entry.getValue()
                                                       .id()
                                                       .equals(id))
                                 .findFirst()
                                 .map(entry -> applyTransition(entry.getKey(),
                                                               entry.getValue(),
                                                               from,
                                                               to))
                                 .orElseGet(() -> Promise.success(Option.empty()));
    }

    private Promise<Option<RowId>> applyTransition(UUID seatId, StoredReservation reservation, String from, String to) {
        if (!reservation.state().equals(from)) {
            return Promise.success(Option.empty());
        }

        reservationsBySeat.put(seatId, withState(reservation, to));

        return Promise.success(Option.present(new RowId(reservation.id())));
    }

    private Promise<Option<RowId>> forceReservationById(UUID id, String to) {
        return reservationsBySeat.entrySet()
                                 .stream()
                                 .filter(entry -> entry.getValue()
                                                       .id()
                                                       .equals(id))
                                 .findFirst()
                                 .map(entry -> force(entry.getKey(),
                                                     entry.getValue(),
                                                     to))
                                 .orElseGet(() -> Promise.success(Option.empty()));
    }

    private Promise<Option<RowId>> force(UUID seatId, StoredReservation reservation, String to) {
        reservationsBySeat.put(seatId, withState(reservation, to));

        return Promise.success(Option.present(new RowId(reservation.id())));
    }

    private StoredReservation withState(StoredReservation reservation, String state) {
        return new StoredReservation(reservation.id(),
                                     reservation.seatId(),
                                     reservation.eventId(),
                                     reservation.customerId(),
                                     state);
    }

    private StoredBooking withStatus(StoredBooking booking, String status) {
        return new StoredBooking(booking.id(),
                                 booking.seatId(),
                                 booking.eventId(),
                                 booking.customerId(),
                                 status,
                                 booking.ticketId());
    }
}
