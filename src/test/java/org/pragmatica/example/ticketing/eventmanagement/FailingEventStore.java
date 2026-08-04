package org.pragmatica.example.ticketing.eventmanagement;

import java.util.UUID;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Fault-injecting fake store: a faithful {@link InMemoryEventStore} that fails a chosen operation
/// (returning a generic store failure the slices map onto their own `StoreUnavailable`), and delegates
/// every other operation to the real in-memory behaviour. Mirrors the booking subsystem's
/// `FailingBookingStore`. Public so the deep-package slice tests can reuse it.
///
/// Injecting a *single* operation matters for the refusal-diagnosis paths: a slice that reacts to a
/// refused transition by reading the row back issues two statements, and only a per-operation fault can
/// tell the two apart. {@link FailOp#ALL} is the blunt whole-store outage.
public final class FailingEventStore extends InMemoryEventStore {
    /// The operation this store fails on; `ALL` fails every operation.
    public enum FailOp {
        ALL,
        INSERT_EVENT,
        INSERT_SEAT,
        OPEN_EVENT,
        CANCEL_EVENT,
        BLOCK_SEAT,
        RELEASE_SEAT,
        FIND_EVENT,
        FIND_SEAT,
        MARK_SEAT_SOLD,
        MARK_SEAT_AVAILABLE
    }

    private final FailOp failOp;

    public FailingEventStore(FailOp failOp) {
        this.failOp = failOp;
    }

    /// Every operation fails -- the whole-store outage used by the `storeFails` tests.
    public static FailingEventStore allOperationsFail() {
        return new FailingEventStore(FailOp.ALL);
    }

    @Override
    public Promise<Unit> insertEvent(UUID id, String venue, String onSaleAt) {
        return fails(FailOp.INSERT_EVENT)
               ? storeDown()
               : super.insertEvent(id, venue, onSaleAt);
    }

    @Override
    public Promise<Unit> insertSeat(UUID id, UUID eventId, String section, String seatRow, int number, String tier) {
        return fails(FailOp.INSERT_SEAT)
               ? storeDown()
               : super.insertSeat(id, eventId, section, seatRow, number, tier);
    }

    @Override
    public Promise<Option<RowId>> openEvent(UUID id) {
        return fails(FailOp.OPEN_EVENT)
               ? storeDown()
               : super.openEvent(id);
    }

    @Override
    public Promise<Option<RowId>> cancelEvent(UUID id) {
        return fails(FailOp.CANCEL_EVENT)
               ? storeDown()
               : super.cancelEvent(id);
    }

    @Override
    public Promise<Option<RowId>> blockSeat(UUID id) {
        return fails(FailOp.BLOCK_SEAT)
               ? storeDown()
               : super.blockSeat(id);
    }

    @Override
    public Promise<Option<RowId>> releaseSeat(UUID id) {
        return fails(FailOp.RELEASE_SEAT)
               ? storeDown()
               : super.releaseSeat(id);
    }

    @Override
    public Promise<Option<EventRow>> findEvent(UUID id) {
        return fails(FailOp.FIND_EVENT)
               ? storeDown()
               : super.findEvent(id);
    }

    @Override
    public Promise<Option<SeatRow>> findSeat(UUID id) {
        return fails(FailOp.FIND_SEAT)
               ? storeDown()
               : super.findSeat(id);
    }

    @Override
    public Promise<Option<RowId>> markSeatSold(long version, UUID id) {
        return fails(FailOp.MARK_SEAT_SOLD)
               ? storeDown()
               : super.markSeatSold(version, id);
    }

    @Override
    public Promise<Option<RowId>> markSeatAvailable(long version, UUID id) {
        return fails(FailOp.MARK_SEAT_AVAILABLE)
               ? storeDown()
               : super.markSeatAvailable(version, id);
    }

    private boolean fails(FailOp op) {
        return failOp == FailOp.ALL || failOp == op;
    }

    private static <T> Promise<T> storeDown() {
        return Causes.cause("store down").promise();
    }
}
