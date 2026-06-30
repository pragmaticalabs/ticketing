package org.pragmatica.example.ticketing.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.vo.Uuid;

import java.util.UUID;


public record SeatId(Uuid value) {
    public sealed interface Error extends Cause {
        record Blank() implements Error {
            @Override
            public String message() {
                return "Seat id must not be blank";
            }
        }

        record Malformed() implements Error {
            @Override
            public String message() {
                return "Seat id must be a valid UUID";
            }
        }

        static Error blank() {
            return new Blank();
        }

        static Error malformed() {
            return new Malformed();
        }
    }

    public static Result<SeatId> seatId(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             Error.blank()).flatMap(present -> Uuid.uuid(present).mapError(_ -> Error.malformed()))
                            .map(SeatId::new);
    }

    public static SeatId seatId(UUID raw) {
        return new SeatId(Uuid.uuid(raw));
    }

    public static SeatId seatId() {
        return new SeatId(Uuid.randomUuid());
    }
}
