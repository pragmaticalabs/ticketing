package org.pragmatica.example.ticketing.booking;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Assertions;


/// Shared in-memory fake of the @PgSql {@link BookingStore}, used by every booking slice test.
///
/// This fake is a **faithful model of the schema**, not a convenience map: it reproduces the
/// constraints Postgres would enforce, so a defect the real database would surface cannot pass here.
/// Specifically it models
///   - the design-out serialization point: reservations are keyed by seat, exactly as `seat_id` is
///     the primary key, so a seat can never acquire a second reservation row;
///   - **key immutability**: the key is the seat and is never rewritten; only the non-key `claim_id`
///     rotates, and it rotates on every successful claim, so a stale claim handle stops matching;
///   - the claim guard verbatim (`state IN ('cancelled','expired')`, an expired hold, or the same
///     customer's live hold) -- an out-of-state claim yields an empty projection;
///   - every guarded lifecycle `UPDATE ... WHERE ...` (an out-of-state or stale-claim row yields
///     empty rather than mutating);
///   - NULL semantics: `expires_at` exists only while a hold is live, and the COALESCE in the decay
///     query turns its absence into "no decay" rather than a NULL bound to a primitive;
///   - the per-seat `version` counter (V008): the reservation row is the one serialization point every
///     seat transition passes through, so the counter is monotonic per seat. It starts at 0 on a fresh
///     insert and is bumped by EVERY state-changing statement -- claim-by-conflict, confirm, release,
///     cancel-by-seat, expire and orphan-reap alike -- and the bumped value is what
///     `ClaimRef`/`SeatRef` return, exactly as the real `RETURNING ... version` does. Publishers stamp
///     facts with it, so a fake that skipped a bump would silently make an unordered fact stream look
///     ordered.
///
/// The real SQL derives the `expired`/`stale` decay flags from `expires_at` relative to `now()` and
/// reaps orphaned confirmations by `created_at` age; this fake has no clock, so both are injectable
/// properties -- {@link #withDecay(Decay)} (default {@link Decay#FRESH}) and
/// {@link #withAgedClaims()} (default: claims are recent). They model two different SQL time
/// predicates and are therefore independent knobs.
///
/// Public (and non-final so the failure-injecting {@link FailingBookingStore} can subclass it) so the
/// deep-package slice tests can reuse it.
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

    /// A reservation row, keyed by seat. `claimId` is the rotating non-key identity; `hasExpiry`
    /// models `expires_at IS NOT NULL`, which holds only while a hold is live or has just lapsed;
    /// `version` is the per-seat sequence bumped by every state-changing statement.
    private record StoredReservation(UUID seatId,
                                     UUID claimId,
                                     UUID eventId,
                                     UUID customerId,
                                     String state,
                                     boolean hasExpiry,
                                     long version) {}

    private record StoredBooking(UUID id,
                                 UUID reservationClaimId,
                                 UUID seatId,
                                 UUID eventId,
                                 UUID customerId,
                                 String status,
                                 UUID ticketId) {}

    private record StoredPayment(UUID id, UUID bookingId, String status, Option<UUID> receiptId) {}

    private final Map<UUID, StoredReservation> reservationsBySeat = new HashMap<>();
    private final Map<UUID, StoredBooking> bookings = new HashMap<>();
    private final Map<UUID, String> tickets = new HashMap<>();
    private final Map<UUID, StoredPayment> payments = new HashMap<>();
    private Decay decay = Decay.FRESH;

    private boolean agedClaims;

    /// Set the decay state reported by {@link #holdDecay(UUID)} and used by the claim guard and the
    /// expiry sweep; fluent so a store can be built inline.
    public InMemoryBookingStore withDecay(Decay decay) {
        this.decay = decay;

        return this;
    }

    /// Treat every stored claim as older than the orphan-reap age bound; fluent.
    public InMemoryBookingStore withAgedClaims() {
        this.agedClaims = true;

        return this;
    }

    /// Seed a held reservation the way a slice would, returning the claim identity the store
    /// generated. Kept here (rather than in each test) so the tests are insulated from the store's
    /// claim signature.
    public UUID seedHold(UUID seatId, UUID eventId, UUID customerId) {
        return claimSeat(seatId, eventId, customerId).await()
                        .onFailure(cause -> Assertions.fail(cause.message()))
                        .or(Option.<ClaimRef> empty())
                        .map(ClaimRef::claimId)
                        .or(() -> Assertions.fail("Seat claim was refused"));
    }

    /// Seed a confirmed reservation with no booking row -- the state a crash between
    /// `confirmReservation` and `insertBooking` leaves behind.
    public UUID seedConfirmedReservation(UUID seatId, UUID eventId, UUID customerId) {
        var claimId = seedHold(seatId, eventId, customerId);

        confirmReservation(claimId).await().onFailure(cause -> Assertions.fail(cause.message()));

        return claimId;
    }

    @Override
    public Promise<Option<ClaimRef>> claimSeat(UUID seatId, UUID eventId, UUID customerId) {
        return Option.option(reservationsBySeat.get(seatId))
                     .map(existing -> reclaim(existing, eventId, customerId))
                     .or(() -> writeClaim(seatId, eventId, customerId, 0L));
    }

    @Override
    public Promise<Option<ClaimRef>> confirmReservation(UUID claimId) {
        return transitionByClaim(claimId, "held", "confirmed", false);
    }

    @Override
    public Promise<Option<ClaimRef>> releaseReservation(UUID claimId) {
        return findReservationByClaim(claimId).map(reservation -> writeState(reservation, "cancelled", false))
                                     .or(() -> Promise.success(Option.empty()));
    }

    @Override
    public Promise<Option<ClaimRef>> cancelReservationBySeat(UUID seatId) {
        return Option.option(reservationsBySeat.get(seatId))
                     .map(existing -> applyTransition(existing, "confirmed", "cancelled", false))
                     .or(() -> Promise.success(Option.empty()));
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
                                       UUID reservationClaimId,
                                       UUID seatId,
                                       UUID eventId,
                                       UUID customerId,
                                       UUID ticketId) {
        bookings.put(id, new StoredBooking(id, reservationClaimId, seatId, eventId, customerId, "confirmed", ticketId));

        return Promise.UNIT;
    }

    @Override
    public Promise<Unit> insertPayment(UUID id,
                                       UUID bookingId,
                                       String status,
                                       UUID receiptId,
                                       long amountMinor,
                                       String currency) {
        payments.put(id, new StoredPayment(id, bookingId, status, Option.option(receiptId)));

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
        return Option.option(bookings.get(id))
                     .map(this::applyBookingCancel)
                     .or(() -> Promise.success(Option.empty()));
    }

    @Override
    public Promise<Unit> invalidateTicket(UUID id) {
        tickets.computeIfPresent(id, (_, _) -> "invalidated");

        return Promise.UNIT;
    }

    @Override
    public Promise<Option<ReceiptRef>> findRefund(UUID bookingId) {
        return Promise.success(refundedPayment(bookingId).flatMap(StoredPayment::receiptId).map(ReceiptRef::new));
    }

    @Override
    public Promise<Unit> markRefunded(UUID receiptId, UUID bookingId) {
        authorizedPayment(bookingId).onPresent(payment -> payments.put(payment.id(), refunded(payment, receiptId)));

        return Promise.UNIT;
    }

    /// Models `SELECT state, COALESCE(expires_at < now(), FALSE) AS expired, ...`: a reservation with
    /// no expiry yields no decay, and the caller decides by `state` whether the flags mean anything.
    @Override
    public Promise<Option<HoldRow>> holdDecay(UUID seatId) {
        return Promise.success(Option.option(reservationsBySeat.get(seatId)).map(reservation -> new HoldRow(reservation.state(),
                                                                                                            expiredFlag(reservation),
                                                                                                            staleFlag(reservation))));
    }

    @Override
    public Promise<List<SeatRef>> expireHolds() {
        return Promise.success(reapAll(expiredHolds(), "expired", true));
    }

    @Override
    public Promise<List<SeatRef>> expireOrphanedConfirmations() {
        return Promise.success(reapAll(orphanedConfirmations(), "cancelled", false));
    }

    public String reservationStateBySeat(UUID seatId) {
        return reservationsBySeat.get(seatId)
                                 .state();
    }

    public UUID claimIdBySeat(UUID seatId) {
        return reservationsBySeat.get(seatId)
                                 .claimId();
    }

    /// The per-seat version counter, for assertions that it advances on every transition.
    public long reservationVersionBySeat(UUID seatId) {
        return reservationsBySeat.get(seatId)
                                 .version();
    }

    public String bookingStatus(UUID bookingId) {
        return bookings.get(bookingId)
                       .status();
    }

    public String ticketStatus(UUID ticketId) {
        return tickets.get(ticketId);
    }

    /// Status of the payment recorded for a booking, or "none" when no payment row exists.
    public String paymentStatus(UUID bookingId) {
        return payments.values()
                       .stream()
                       .filter(payment -> payment.bookingId()
                                                 .equals(bookingId))
                       .map(StoredPayment::status)
                       .findFirst()
                       .orElse("none");
    }

    /// Models the reap statements' `SET state = ..., version = version + 1 RETURNING seat_id, event_id,
    /// version`: each reaped row is bumped and reports the version of the transition that freed it, so
    /// the published fact carries the position the reap occupies in that seat's sequence.
    private List<SeatRef> reapAll(List<StoredReservation> matched, String state, boolean keepExpiry) {
        return matched.stream()
                      .map(reservation -> reap(reservation, state, keepExpiry))
                      .toList();
    }

    private SeatRef reap(StoredReservation reservation, String state, boolean keepExpiry) {
        var reaped = withState(reservation, state, keepExpiry && reservation.hasExpiry());

        reservationsBySeat.put(reaped.seatId(), reaped);

        return new SeatRef(reaped.seatId(), reaped.eventId(), reaped.version());
    }

    private List<StoredReservation> expiredHolds() {
        return matching(this::expiredHold);
    }

    private List<StoredReservation> orphanedConfirmations() {
        return matching(this::orphanedConfirmation);
    }

    /// Materialised before any row is rewritten, exactly as the real single-statement `UPDATE ... WHERE`
    /// selects its target set once.
    private List<StoredReservation> matching(Predicate<StoredReservation> predicate) {
        return reservationsBySeat.values()
                                 .stream()
                                 .filter(predicate)
                                 .toList();
    }

    private boolean expiredHold(StoredReservation reservation) {
        return reservation.state()
                          .equals("held") && decay.expired();
    }

    /// `state = 'confirmed' AND created_at < now() - interval '1 hour' AND claim_id NOT IN (confirmed
    /// bookings' claims)`.
    private boolean orphanedConfirmation(StoredReservation reservation) {
        return reservation.state()
                          .equals("confirmed")
               && agedClaims
               && !soldUnderClaim(reservation.claimId());
    }

    private boolean soldUnderClaim(UUID claimId) {
        return bookings.values()
                       .stream()
                       .filter(booking -> booking.status()
                                                 .equals("confirmed"))
                       .anyMatch(booking -> booking.reservationClaimId()
                                                   .equals(claimId));
    }

    private boolean expiredFlag(StoredReservation reservation) {
        return reservation.hasExpiry() && (reservation.state()
                                                      .equals("expired") || decay.expired());
    }

    private boolean staleFlag(StoredReservation reservation) {
        return reservation.hasExpiry() && (reservation.state()
                                                      .equals("expired") || decay.stale());
    }

    private Option<StoredPayment> refundedPayment(UUID bookingId) {
        return paymentFor(bookingId, "refunded");
    }

    private Option<StoredPayment> authorizedPayment(UUID bookingId) {
        return paymentFor(bookingId, "authorized");
    }

    private Option<StoredPayment> paymentFor(UUID bookingId, String status) {
        return Option.from(payments.values()
                                   .stream()
                                   .filter(payment -> payment.bookingId()
                                                             .equals(bookingId))
                                   .filter(payment -> payment.status()
                                                             .equals(status))
                                   .findFirst());
    }

    private StoredPayment refunded(StoredPayment payment, UUID receiptId) {
        return new StoredPayment(payment.id(), payment.bookingId(), "refunded", Option.present(receiptId));
    }

    /// A claim always writes a FRESH claim identity; the seat key itself is never rewritten. The
    /// version is supplied by the caller: 0 for the plain INSERT, `reservations.version + 1` for the
    /// ON CONFLICT branch.
    private Promise<Option<ClaimRef>> writeClaim(UUID seatId, UUID eventId, UUID customerId, long version) {
        var claimId = UUID.randomUUID();

        reservationsBySeat.put(seatId,
                               new StoredReservation(seatId, claimId, eventId, customerId, "held", true, version));

        return claimed(claimId, version);
    }

    private Promise<Option<ClaimRef>> reclaim(StoredReservation existing, UUID eventId, UUID customerId) {
        return claimable(existing, customerId)
               ? writeClaim(existing.seatId(), eventId, customerId, existing.version() + 1)
               : Promise.success(Option.empty());
    }

    /// The claim guard verbatim: a cancelled or expired reservation, a hold past its TTL, or the same
    /// customer's own live hold. Never a confirmed sale, never another customer's live hold.
    private boolean claimable(StoredReservation existing, UUID customerId) {
        return existing.state()
                       .equals("cancelled") || existing.state()
                                                       .equals("expired") || expiredHold(existing) || ownLiveHold(existing,
                                                                                                                  customerId);
    }

    private boolean ownLiveHold(StoredReservation existing, UUID customerId) {
        return existing.state()
                       .equals("held") && existing.customerId()
                                                  .equals(customerId);
    }

    private Promise<Option<RowId>> applyBookingCancel(StoredBooking existing) {
        return existing.status()
                       .equals("confirmed")
               ? cancelBookingRow(existing)
               : Promise.success(Option.empty());
    }

    private Promise<Option<RowId>> cancelBookingRow(StoredBooking existing) {
        bookings.put(existing.id(), withStatus(existing, "cancelled"));

        return Promise.success(Option.present(new RowId(existing.id())));
    }

    private Promise<Option<ClaimRef>> transitionByClaim(UUID claimId, String from, String to, boolean hasExpiry) {
        return findReservationByClaim(claimId).map(reservation -> applyTransition(reservation, from, to, hasExpiry))
                                     .or(() -> Promise.success(Option.empty()));
    }

    private Promise<Option<ClaimRef>> applyTransition(StoredReservation reservation,
                                                      String from,
                                                      String to,
                                                      boolean hasExpiry) {
        return reservation.state()
                          .equals(from)
               ? writeState(reservation, to, hasExpiry)
               : Promise.success(Option.empty());
    }

    private Promise<Option<ClaimRef>> writeState(StoredReservation reservation, String to, boolean hasExpiry) {
        var written = withState(reservation, to, hasExpiry);

        reservationsBySeat.put(written.seatId(), written);

        return claimed(written.claimId(), written.version());
    }

    private Option<StoredReservation> findReservationByClaim(UUID claimId) {
        return Option.option(reservationsBySeat.values()
                                               .stream()
                                               .filter(reservation -> reservation.claimId()
                                                                                 .equals(claimId))
                                               .findFirst()
                                               .orElse(null));
    }

    /// The single place a stored row changes state, so `version = version + 1` here is what makes the
    /// counter monotonic across every transition without any caller having to remember to bump it.
    private StoredReservation withState(StoredReservation reservation, String state, boolean hasExpiry) {
        return new StoredReservation(reservation.seatId(),
                                     reservation.claimId(),
                                     reservation.eventId(),
                                     reservation.customerId(),
                                     state,
                                     hasExpiry,
                                     reservation.version() + 1);
    }

    private StoredBooking withStatus(StoredBooking booking, String status) {
        return new StoredBooking(booking.id(),
                                 booking.reservationClaimId(),
                                 booking.seatId(),
                                 booking.eventId(),
                                 booking.customerId(),
                                 status,
                                 booking.ticketId());
    }

    private static Promise<Option<ClaimRef>> claimed(UUID claimId, long version) {
        return Promise.success(Option.present(new ClaimRef(claimId, version)));
    }
}
