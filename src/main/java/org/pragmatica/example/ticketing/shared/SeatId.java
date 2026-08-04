package org.pragmatica.example.ticketing.shared;

import java.util.UUID;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.vo.Uuid;


public record SeatId(Uuid value) {
    public sealed interface Error extends Cause {
        /// Fixed-message parse refusals for this id. Both are pure syntax failures on client input, and
        /// every slice re-declares them as its own HTTP 400 cause before answering, so they are grouped
        /// under one enum named for that single routing outcome rather than a catch-all `General`.
        enum Invalid implements Error {
            BLANK("Seat id must not be blank"),
            MALFORMED("Seat id must be a valid UUID");
            private final String message;
            Invalid(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        static Error blank() {
            return Invalid.BLANK;
        }

        static Error malformed() {
            return Invalid.MALFORMED;
        }
    }

    public static Result<SeatId> seatId(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             Error.blank())
                     .flatMap(present -> Uuid.uuid(present).mapError(_ -> Error.malformed()))
                     .map(SeatId::new);
    }

    public static SeatId seatId(UUID raw) {
        return new SeatId(Uuid.uuid(raw));
    }

    public static SeatId seatId() {
        return new SeatId(Uuid.randomUuid());
    }
}
