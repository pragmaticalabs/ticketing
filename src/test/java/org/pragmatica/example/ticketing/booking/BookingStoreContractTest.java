package org.pragmatica.example.ticketing.booking;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Contract tests for the store model itself, rather than for a slice.
///
/// The in-memory {@link InMemoryBookingStore} stands in for the real schema in every booking slice
/// test, so the guarantees the schema provides have to be guarantees the fake provides too --
/// otherwise a defect the database would raise passes here unnoticed. These tests pin the four that
/// the slices depend on and that a convenience map would silently drop: the seat key is immutable
/// and reusable, the claim identity rotates on every claim (making a stale handle fail closed), the
/// claim guard opens for exactly one extra case (the same customer's own live hold), and the per-seat
/// `version` counter advances on every state transition and is reported by the transition that made
/// it -- which is what lets a publisher stamp a fact with an orderable position.
class BookingStoreContractTest {
    private final UUID seat = UUID.randomUUID();
    private final UUID event = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();

    @Test
    void claimSeat_freeSeat_returnsClaim() {
        var store = new InMemoryBookingStore();

        store.claimSeat(seat, event, customer)
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(claimed -> assertThat(claimed.isPresent()).isTrue());
    }

    // The seat key is never rewritten, so the history of previous claims on a seat can never block a
    // new one: a cancelled reservation is reclaimable however many bookings recorded its past claims.
    @Test
    void claimSeat_afterCancellation_succeeds() {
        var store = new InMemoryBookingStore();
        var firstClaim = store.seedHold(seat, event, customer);

        store.insertBooking(UUID.randomUUID(),
                            firstClaim,
                            seat,
                            event,
                            customer,
                            UUID.randomUUID())
             .await()
             .onFailure(cause -> fail(cause.message()));
        store.releaseReservation(firstClaim).await().onFailure(cause -> fail(cause.message()));
        store.claimSeat(seat,
                        event,
                        UUID.randomUUID())
             .await()
             .onFailure(cause -> fail("A cancelled seat must be reclaimable: " + cause.message()))
             .onSuccess(claimed -> assertThat(claimed.isPresent()).isTrue());
    }

    @Test
    void claimSeat_reclaim_rotatesClaimId() {
        var store = new InMemoryBookingStore();
        var firstClaim = store.seedHold(seat, event, customer);

        store.releaseReservation(firstClaim).await().onFailure(cause -> fail(cause.message()));
        var secondClaim = store.seedHold(seat, event, customer);

        assertThat(secondClaim).isNotEqualTo(firstClaim);
        assertThat(store.claimIdBySeat(seat)).isEqualTo(secondClaim);
    }

    @Test
    void claimSeat_ownLiveHold_succeeds() {
        var store = new InMemoryBookingStore();

        store.seedHold(seat, event, customer);
        store.claimSeat(seat, event, customer)
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(claimed -> assertThat(claimed.isPresent()).isTrue());
    }

    @Test
    void claimSeat_otherCustomerLiveHold_returnsEmpty() {
        var store = new InMemoryBookingStore();

        store.seedHold(seat, event, customer);
        store.claimSeat(seat,
                        event,
                        UUID.randomUUID())
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(claimed -> assertThat(claimed.isEmpty()).isTrue());
    }

    @Test
    void claimSeat_confirmedReservation_returnsEmpty() {
        var store = new InMemoryBookingStore();

        store.seedConfirmedReservation(seat, event, customer);
        store.claimSeat(seat, event, customer)
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(claimed -> assertThat(claimed.isEmpty()).isTrue());
    }

    @Test
    void claimSeat_expiredHold_succeeds() {
        var store = new InMemoryBookingStore().withDecay(InMemoryBookingStore.Decay.EXPIRED);

        store.seedHold(seat, event, customer);
        store.claimSeat(seat,
                        event,
                        UUID.randomUUID())
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(claimed -> assertThat(claimed.isPresent()).isTrue());
    }

    // Rotation is what makes a stale handle safe: the saga that lost its claim can no longer confirm
    // or release the reservation somebody else now owns.
    @Test
    void confirmReservation_supersededClaim_returnsEmpty() {
        var store = new InMemoryBookingStore();
        var firstClaim = store.seedHold(seat, event, customer);

        store.seedHold(seat, event, customer);
        store.confirmReservation(firstClaim)
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(confirmed -> assertThat(confirmed.isEmpty()).isTrue());
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("held");
    }

    @Test
    void releaseReservation_supersededClaim_returnsEmpty() {
        var store = new InMemoryBookingStore();
        var firstClaim = store.seedHold(seat, event, customer);

        store.seedHold(seat, event, customer);
        store.releaseReservation(firstClaim)
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(released -> assertThat(released.isEmpty()).isTrue());
        assertThat(store.reservationStateBySeat(seat)).isEqualTo("held");
    }

    // A confirmed reservation has no expiry, so the decay flags must read as "no decay" rather than
    // failing on a NULL bound to a primitive.
    @Test
    void holdDecay_confirmedReservation_reportsNoDecay() {
        var store = new InMemoryBookingStore();

        store.seedConfirmedReservation(seat, event, customer);
        store.holdDecay(seat)
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(found -> found.onPresent(row -> {
                                                     assertThat(row.state()).isEqualTo("confirmed");
                                                     assertThat(row.expired()).isFalse();
                                                     assertThat(row.stale()).isFalse();
                                                 })
                                      .onEmpty(() -> fail("Expected a decay snapshot")));
    }

    /// The per-seat sequence the seat facts are ordered by. It has to advance on EVERY transition the
    /// seat passes through, because a consumer's guard compares versions from different transitions:
    /// a lifecycle step that forgot to bump would make two distinct transitions indistinguishable and
    /// let the later one be discarded as stale.
    @Test
    void reservationVersion_holdConfirmCancelReclaim_strictlyIncreases() {
        var store = new InMemoryBookingStore();
        var claim = store.seedHold(seat, event, customer);
        var afterHold = store.reservationVersionBySeat(seat);

        store.confirmReservation(claim).await().onFailure(cause -> fail(cause.message()));
        var afterConfirm = store.reservationVersionBySeat(seat);

        store.cancelReservationBySeat(seat).await().onFailure(cause -> fail(cause.message()));
        var afterCancel = store.reservationVersionBySeat(seat);

        store.seedHold(seat, event, UUID.randomUUID());
        var afterReclaim = store.reservationVersionBySeat(seat);

        assertThat(afterHold).isZero();
        assertThat(afterConfirm).isGreaterThan(afterHold);
        assertThat(afterCancel).isGreaterThan(afterConfirm);
        assertThat(afterReclaim).isGreaterThan(afterCancel);
    }

    // The transition reports the version it wrote, not the one it replaced: that returned value is
    // what BuyTicket stamps onto the SeatSold fact.
    @Test
    void confirmReservation_heldReservation_returnsBumpedVersion() {
        var store = new InMemoryBookingStore();
        var claim = store.seedHold(seat, event, customer);

        store.confirmReservation(claim)
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(confirmed -> confirmed.onEmpty(() -> fail("Expected the confirmation to apply"))
                                              .onPresent(ref -> assertThat(ref.version()).isEqualTo(1L)));
    }

    // Same obligation on the sweep path, which publishes SeatReleased for every row it frees.
    @Test
    void expireHolds_expiredHold_returnsBumpedVersion() {
        var store = new InMemoryBookingStore().withDecay(InMemoryBookingStore.Decay.EXPIRED);

        store.seedHold(seat, event, customer);
        store.expireHolds()
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(freed -> assertThat(freed).singleElement()
                                           .satisfies(ref -> assertThat(ref.version()).isEqualTo(1L)));
    }

    @Test
    void findRefund_afterMarkRefunded_returnsReceipt() {
        var store = new InMemoryBookingStore();
        var booking = UUID.randomUUID();
        var receipt = UUID.randomUUID();

        store.insertPayment(UUID.randomUUID(),
                            booking,
                            "authorized",
                            UUID.randomUUID(),
                            2500,
                            "USD")
             .await()
             .onFailure(cause -> fail(cause.message()));
        store.findRefund(booking)
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(found -> assertThat(found.isEmpty()).isTrue());
        store.markRefunded(receipt, booking).await().onFailure(cause -> fail(cause.message()));
        store.findRefund(booking)
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(found -> assertThat(found.or(() -> new BookingStore.ReceiptRef(UUID.randomUUID())).receiptId()).isEqualTo(receipt));
    }
}
