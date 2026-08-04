package org.pragmatica.example.ticketing.eventmanagement.capacity.seatsellability;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.eventmanagement.EventStore.SeatRow;
import org.pragmatica.example.ticketing.shared.SeatId;
import org.pragmatica.example.ticketing.shared.SeatState;


/// Use case: read whether a seat may be sold at all (direct read, also called by booking). Telescope
/// leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `capacity` -> use case
/// `seat-sellability`. One use case, one `Request`/`Response` pair, one `execute` method.
///
/// This is the seat-level counterpart of `sale-status`, and it exists for the same reason: the
/// `seats` table is owned by event-management, so booking must not read it. `block-seat` and
/// `release-seat` write authoritative seat state here; without a read beside them nothing consults
/// it, and an operator who blocks a seat changes a column no sale path ever looks at.
///
/// **What `sellable` means, precisely.** It answers only the question event-management is authoritative
/// for -- *has this seat been withheld from sale by the operator?* -- and it is decided by an
/// exhaustive switch over [SeatState], so a new lifecycle state cannot be added without deciding its
/// answer here:
///   - `BLOCKED` / `WITHDRAWN` -> not sellable: an operator (house seat, damage, catalogue withdrawal)
///     took the seat out of inventory;
///   - `AVAILABLE` -> sellable;
///   - `SOLD` -> **also sellable**, deliberately. Seat state converges from booking's `SeatSold` /
///     `SeatReleased` facts and therefore lags a cancellation; booking's own reservation row is the
///     serialization point that decides sold-ness, lag-free. Refusing on `SOLD` here would add a
///     window after every cancellation in which the seat is genuinely free but unsellable, and would
///     buy nothing -- the claim guard already refuses a seat with a confirmed reservation.
///
/// An unknown seat is [SeatSellabilityError.SeatNotFound] rather than a `sellable = false` answer:
/// event-management owns the seat catalogue, so "no such seat" is a different fact from "this seat is
/// withheld", and each caller decides what to do with it.
@Slice
public interface SeatSellability {
    record Request(String seat) {}

    record Response(String seat, String state, boolean sellable) {}

    /// Closed set of sellability-read failures. Each is a distinct record so route error-mapping can
    /// target it by simple name (see routes.toml).
    sealed interface SeatSellabilityError extends Cause {
        record SeatNotFound() implements SeatSellabilityError {
            @Override
            public String message() {
                return "Seat not found";
            }
        }

        record StoreUnavailable() implements SeatSellabilityError {
            @Override
            public String message() {
                return "Event management store is unavailable";
            }
        }

        /// Client-facing validation refusal (HTTP 400): a request field could not be parsed into its
        /// domain type. Declared in this slice's own hierarchy instead of letting the shared
        /// value-object cause through, because the slice processor builds the router's error switch
        /// from the `Cause` types in this package alone -- a shared cause arrives unmatched and falls
        /// through to HTTP 500. Data-carrying, so the response names the offending field and keeps the
        /// original reason.
        record InvalidRequest(String field, String detail) implements SeatSellabilityError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        static SeatSellabilityError seatNotFound() {
            return new SeatNotFound();
        }

        static SeatSellabilityError storeUnavailable() {
            return new StoreUnavailable();
        }

        static SeatSellabilityError invalidSeat(Cause cause) {
            return new InvalidRequest("seat", cause.message());
        }
    }

    Promise<Response> execute(Request request);

    static SeatSellability seatSellability(@PgSql EventStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record seatSellability(EventStore store) implements SeatSellability {
            // JBCT pattern: Sequencer -- validate -> read authoritative seat state.
            @Override
            public Promise<Response> execute(Request request) {
                return SeatId.seatId(request.seat())
                             .mapError(SeatSellabilityError::invalidSeat)
                             .async()
                             .flatMap(this::loadSellability);
            }

            private Promise<Response> loadSellability(SeatId seatId) {
                var seatString = seatId.value().value().toString();

                return store.findSeat(seatId.value().value())
                            .mapError(_ -> SeatSellabilityError.storeUnavailable())
                            .flatMap(found -> found.async(SeatSellabilityError.seatNotFound()))
                            .map(row -> response(seatString, row));
            }

            private Response response(String seat, SeatRow row) {
                return new Response(seat,
                                    row.state().dbValue(),
                                    sellable(row.state()));
            }

            /// Exhaustive by construction: a new [SeatState] variant fails to compile until this slice
            /// says whether it may be sold.
            private static boolean sellable(SeatState state) {
                return switch (state) {
                    case AVAILABLE, SOLD -> true;
                    case BLOCKED, WITHDRAWN -> false;
                };
            }
        }

        return new seatSellability(store);
    }
}
