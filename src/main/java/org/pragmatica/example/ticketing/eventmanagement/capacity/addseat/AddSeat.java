package org.pragmatica.example.ticketing.eventmanagement.capacity.addseat;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.example.ticketing.eventmanagement.EventStatus;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.PriceTier;
import org.pragmatica.example.ticketing.shared.SeatId;
import org.pragmatica.example.ticketing.shared.SeatLocation;
import org.pragmatica.example.ticketing.shared.Validation;


/// Use case: add a seat to an existing event in 'available' state.
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `capacity` -> use
/// case `add-seat`. All-body request form: every field binds to a request component by name.
///
/// Guarantee actually earned: the event is gated on its lifecycle **status**, not merely on its
/// existence. A cancelled event is terminal and can no longer grow capacity, so it is refused with
/// [AddSeatError.LifecycleConflict#EVENT_CANCELLED] (HTTP 409); a draft or on-sale event accepts seats.
/// Gating on existence alone -- the previous behaviour -- let seats be added to a cancelled event.
///
/// The refusal is declared in this slice's own error hierarchy rather than shared with the lifecycle
/// slices: this gate reads the status, it does not run a guarded transition, so it can never race a
/// concurrent change the way `open-event`/`cancel-event` can, and its closed failure set must not admit
/// a refusal it cannot produce.
@Slice
public interface AddSeat {
    record Request(String event, String section, String row, int number, String tier) {}

    record Response(String seat) {}

    record ValidAddSeat(EventId event, SeatLocation location, PriceTier tier) {
        // Parse raw request fields into value objects; all errors surface together via Result.all.
        // Each is mapped to a cause declared in this slice's own package and the composite Result.all
        // wraps them in is unwrapped again, because the generated router's error switch is built from
        // this package's `Cause` types alone -- a shared cause, or the composite, falls through to
        // HTTP 500 rather than reaching the client as a refusal it can act on.
        static Result<ValidAddSeat> validAddSeat(Request request) {
            return Result.all(EventId.eventId(request.event()).mapError(AddSeatError::invalidEvent),
                              SeatLocation.seatLocation(request.section(),
                                                        request.row(),
                                                        request.number())
                                          .mapError(AddSeatError::invalidLocation),
                              PriceTier.priceTier(request.tier()).mapError(AddSeatError::unacceptableTier))
                         .map(ValidAddSeat::new)
                         .mapError(Validation::firstFailure);
        }
    }

    sealed interface AddSeatError extends Cause {
        /// Fixed-message refusals by the event-status gate. Every constant here is a conflict on the
        /// event's current status and therefore HTTP 409, which is why the group is named for the routing
        /// rule rather than called `General`: a cause that is *not* a 409 must not be added to it, and
        /// this slice's `routes.toml` maps the whole enum with one `*LifecycleConflict*` pattern.
        enum LifecycleConflict implements AddSeatError {
            EVENT_CANCELLED("Event is cancelled");
            private final String message;
            LifecycleConflict(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        record EventNotFound() implements AddSeatError {
            @Override
            public String message() {
                return "Event not found";
            }
        }

        record StoreUnavailable() implements AddSeatError {
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
        record InvalidRequest(String field, String detail) implements AddSeatError {
            @Override
            public String message() {
                return "Invalid request field '" + field + "': " + detail;
            }
        }

        /// Client-facing validation refusal (HTTP 422): a request field parsed cleanly but its value
        /// lies outside the field's admissible domain -- a seat `number` that is not positive, or a
        /// well-formed token naming no member of the closed `PriceTier` set. Well-formed-but-unacceptable
        /// is a semantic refusal rather than a syntax error, which is why it earns 422 where
        /// [InvalidRequest] earns 400.
        record UnacceptableValue(String field, String detail) implements AddSeatError {
            @Override
            public String message() {
                return "Unacceptable value for request field '" + field + "': " + detail;
            }
        }

        static AddSeatError eventNotFound() {
            return new EventNotFound();
        }

        static AddSeatError storeUnavailable() {
            return new StoreUnavailable();
        }

        /// The event is cancelled -- a terminal status that can no longer grow capacity.
        static AddSeatError eventCancelled() {
            return LifecycleConflict.EVENT_CANCELLED;
        }

        static AddSeatError invalidEvent(Cause cause) {
            return new InvalidRequest("event", cause.message());
        }

        static AddSeatError unacceptableTier(Cause cause) {
            return new UnacceptableValue("tier", cause.message());
        }

        /// `SeatLocation` validates its three fields with its own `Result.all`, so the cause arrives
        /// composite; it is unwrapped before the field that was rejected can be named.
        static AddSeatError invalidLocation(Cause cause) {
            return locationFailure(Validation.firstFailure(cause));
        }

        /// A blank section or row is unparseable text (400); a non-positive seat number is a
        /// well-formed `int` outside the admissible domain (422).
        private static AddSeatError locationFailure(Cause cause) {
            return switch (cause) {
                case SeatLocation.Error.BlankSection _ -> new InvalidRequest("section", cause.message());
                case SeatLocation.Error.BlankRow _ -> new InvalidRequest("row", cause.message());
                case SeatLocation.Error.NonPositiveNumber _ -> new UnacceptableValue("number", cause.message());
                default -> new InvalidRequest("location", cause.message());
            };
        }
    }

    Promise<Response> execute(Request request);

    static AddSeat addSeat(@PgSql EventStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record addSeat(EventStore store) implements AddSeat {
            // JBCT pattern: Sequencer -- validate -> gate on event status -> insert seat.
            @Override
            public Promise<Response> execute(Request request) {
                return ValidAddSeat.validAddSeat(request)
                                   .async()
                                   .flatMap(this::gateOnEventStatusThenInsert);
            }

            private Promise<Response> gateOnEventStatusThenInsert(ValidAddSeat valid) {
                return store.findEvent(valid.event().value().value())
                            .mapError(_ -> AddSeatError.storeUnavailable())
                            .flatMap(found -> found.async(AddSeatError.eventNotFound()))
                            .flatMap(row -> insertIfEventAcceptsSeats(row.status(),
                                                                      valid));
            }

            // JBCT pattern: Condition -- route on the event's lifecycle status, no transformation.
            private Promise<Response> insertIfEventAcceptsSeats(EventStatus status, ValidAddSeat valid) {
                return status == EventStatus.CANCELLED
                       ? AddSeatError.eventCancelled().promise()
                       : insertSeat(valid);
            }

            private Promise<Response> insertSeat(ValidAddSeat valid) {
                var seatId = SeatId.seatId();
                var uuid = seatId.value().value();

                return store.insertSeat(uuid,
                                        valid.event().value().value(),
                                        valid.location().section(),
                                        valid.location().row(),
                                        valid.location().number(),
                                        valid.tier().name())
                            .mapError(_ -> AddSeatError.storeUnavailable())
                            .map(_ -> new Response(uuid.toString()));
            }
        }

        return new addSeat(store);
    }
}
