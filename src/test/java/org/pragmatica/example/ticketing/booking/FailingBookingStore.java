package org.pragmatica.example.ticketing.booking;

import java.util.List;
import java.util.UUID;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Fault-injecting fake store: a faithful {@link InMemoryBookingStore} that fails exactly one named
/// operation (returning a generic store failure the slices map to their own `StoreUnavailable`), and
/// delegates every other operation to the real in-memory behaviour. Used to drive the store-failure
/// and BER-compensation paths deterministically.
///
/// {@link #heal()} clears the injected fault while keeping all stored data, which is how the
/// retry-safety tests re-drive an operation that failed midway: the caller sees the same rows the
/// failed attempt left behind, exactly as a real retry against a recovered database would.
/// Public so the deep-package slice tests can reuse it.
public final class FailingBookingStore extends InMemoryBookingStore {
    /// The single operation this store fails on.
    public enum FailOp {
        NONE,
        CLAIM_SEAT,
        ACTIVE_BOOKING_COUNT,
        INSERT_BOOKING,
        FIND_BOOKING,
        EXPIRE_HOLDS,
        EXPIRE_ORPHANS,
        CANCEL_BOOKING,
        INVALIDATE_TICKET,
        MARK_REFUNDED
    }

    private FailOp failOp;

    public FailingBookingStore(FailOp failOp) {
        this.failOp = failOp;
    }

    /// Clear the injected fault, keeping every stored row; fluent so a retry can be driven inline.
    public FailingBookingStore heal() {
        this.failOp = FailOp.NONE;

        return this;
    }

    @Override
    public Promise<Option<ClaimRef>> claimSeat(UUID seatId, UUID eventId, UUID customerId) {
        return failOp == FailOp.CLAIM_SEAT
               ? storeDown()
               : super.claimSeat(seatId, eventId, customerId);
    }

    @Override
    public Promise<Long> activeBookingCount(UUID customerId) {
        return failOp == FailOp.ACTIVE_BOOKING_COUNT
               ? storeDown()
               : super.activeBookingCount(customerId);
    }

    @Override
    public Promise<Unit> insertBooking(UUID id,
                                       UUID reservationClaimId,
                                       UUID seatId,
                                       UUID eventId,
                                       UUID customerId,
                                       UUID ticketId) {
        return failOp == FailOp.INSERT_BOOKING
               ? storeDown()
               : super.insertBooking(id, reservationClaimId, seatId, eventId, customerId, ticketId);
    }

    @Override
    public Promise<Option<BookingRow>> findBooking(UUID id) {
        return failOp == FailOp.FIND_BOOKING
               ? storeDown()
               : super.findBooking(id);
    }

    @Override
    public Promise<List<SeatRef>> expireHolds() {
        return failOp == FailOp.EXPIRE_HOLDS
               ? storeDown()
               : super.expireHolds();
    }

    @Override
    public Promise<List<SeatRef>> expireOrphanedConfirmations() {
        return failOp == FailOp.EXPIRE_ORPHANS
               ? storeDown()
               : super.expireOrphanedConfirmations();
    }

    @Override
    public Promise<Option<RowId>> cancelBooking(UUID id) {
        return failOp == FailOp.CANCEL_BOOKING
               ? storeDown()
               : super.cancelBooking(id);
    }

    @Override
    public Promise<Unit> invalidateTicket(UUID id) {
        return failOp == FailOp.INVALIDATE_TICKET
               ? storeDown()
               : super.invalidateTicket(id);
    }

    @Override
    public Promise<Unit> markRefunded(UUID receiptId, UUID bookingId) {
        return failOp == FailOp.MARK_REFUNDED
               ? storeDown()
               : super.markRefunded(receiptId, bookingId);
    }

    private static <T> Promise<T> storeDown() {
        return Causes.cause("store down").promise();
    }
}
