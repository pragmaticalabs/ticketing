package org.pragmatica.example.ticketing.eventmanagement.capacity.addseat;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.example.ticketing.eventmanagement.EventStore;
import org.pragmatica.example.ticketing.shared.EventId;
import org.pragmatica.example.ticketing.shared.PriceTier;
import org.pragmatica.example.ticketing.shared.SeatId;
import org.pragmatica.example.ticketing.shared.SeatLocation;


/// Use case: add a seat to an existing event in 'available' state.
/// Telescope leaf -- system `ticketing` -> subsystem `eventmanagement` -> workflow `capacity` -> use
/// case `add-seat`. All-body request form: every field binds to a request component by name.
@Slice
public interface AddSeat {
    @SuppressWarnings("JBCT-VO-01")
    record Request(String event, String section, String row, int number, String tier) {}

    @SuppressWarnings("JBCT-VO-01")
    record Response(String seat) {}

    record ValidAddSeat(EventId event, SeatLocation location, PriceTier tier) {
        // Parse raw request fields into value objects; all errors surface together via Result.all.
        static Result<ValidAddSeat> validAddSeat(Request request) {
            return Result.all(EventId.eventId(request.event()),
                              SeatLocation.seatLocation(request.section(),
                                                        request.row(),
                                                        request.number()),
                              PriceTier.priceTier(request.tier()))
                         .map(ValidAddSeat::new);
        }
    }

    sealed interface AddSeatError extends Cause {
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

        static AddSeatError eventNotFound() {
            return new EventNotFound();
        }

        static AddSeatError storeUnavailable() {
            return new StoreUnavailable();
        }
    }

    Promise<Response> execute(Request request);

    static AddSeat addSeat(@PgSql EventStore store) {
        @SuppressWarnings("JBCT-SEQ-01")
        record addSeat(EventStore store) implements AddSeat {
            // JBCT pattern: Sequencer -- validate -> ensure event exists -> insert seat.
            @Override
            public Promise<Response> execute(Request request) {
                return ValidAddSeat.validAddSeat(request)
                                   .async()
                                   .flatMap(this::ensureEventThenInsert);
            }

            private Promise<Response> ensureEventThenInsert(ValidAddSeat valid) {
                return store.eventExists(valid.event().value().value())
                            .mapError(_ -> AddSeatError.storeUnavailable())
                            .flatMap(exists -> insertSeatIfEventExists(exists, valid));
            }

            // JBCT pattern: Condition -- route on event existence, no transformation.
            private Promise<Response> insertSeatIfEventExists(boolean exists, ValidAddSeat valid) {
                return exists
                       ? insertSeat(valid)
                       : AddSeatError.eventNotFound().promise();
            }

            private Promise<Response> insertSeat(ValidAddSeat valid) {
                var seatId = SeatId.seatId();
                var uuid = seatId.value().value();

                return store.insertSeat(uuid,
                                        valid.event().value().value(),
                                        valid.location().section(),
                                        valid.location().row(),
                                        valid.location().number(),
                                        valid.tier().name()).mapError(_ -> AddSeatError.storeUnavailable())
                                       .map(_ -> new Response(uuid.toString()));
            }
        }

        return new addSeat(store);
    }
}
