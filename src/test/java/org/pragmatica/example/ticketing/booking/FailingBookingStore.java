package org.pragmatica.example.ticketing.booking;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.UUID;


/// Fault-injecting fake store: a faithful {@link InMemoryBookingStore} that fails exactly one named
/// operation (returning a generic store failure the slices map to their own `StoreUnavailable`), and
/// delegates every other operation to the real in-memory behaviour. Used to drive the store-failure
/// and BER-compensation paths deterministically. Public so the deep-package slice tests can reuse it.
public final class FailingBookingStore extends InMemoryBookingStore {
    /// The single operation this store fails on.
    public enum FailOp {
        CLAIM_SEAT,
        ACTIVE_BOOKING_COUNT,
        INSERT_BOOKING,
        FIND_BOOKING,
        EXPIRE_HOLDS
    }

    private final FailOp failOp;

    public FailingBookingStore(FailOp failOp) {
        this.failOp = failOp;
    }

    @Override
    public Promise<Option<RowId>> claimSeat(UUID id, UUID seatId, UUID eventId, UUID customerId) {
        return failOp == FailOp.CLAIM_SEAT
               ? storeDown()
               : super.claimSeat(id, seatId, eventId, customerId);
    }

    @Override
    public Promise<Long> activeBookingCount(UUID customerId) {
        return failOp == FailOp.ACTIVE_BOOKING_COUNT
               ? storeDown()
               : super.activeBookingCount(customerId);
    }

    @Override
    public Promise<Unit> insertBooking(UUID id,
                                       UUID reservationId,
                                       UUID seatId,
                                       UUID eventId,
                                       UUID customerId,
                                       UUID ticketId) {
        return failOp == FailOp.INSERT_BOOKING
               ? storeDown()
               : super.insertBooking(id, reservationId, seatId, eventId, customerId, ticketId);
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

    private static <T> Promise<T> storeDown() {
        return Causes.cause("store down").promise();
    }
}
