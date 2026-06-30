package org.pragmatica.example.ticketing.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.vo.Uuid;

import java.util.UUID;


public record BookingId(Uuid value) {
    public sealed interface Error extends Cause {
        record Blank() implements Error {
            @Override
            public String message() {
                return "Booking id must not be blank";
            }
        }

        record Malformed() implements Error {
            @Override
            public String message() {
                return "Booking id must be a valid UUID";
            }
        }

        static Error blank() {
            return new Blank();
        }

        static Error malformed() {
            return new Malformed();
        }
    }

    public static Result<BookingId> bookingId(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             Error.blank()).flatMap(present -> Uuid.uuid(present).mapError(_ -> Error.malformed()))
                            .map(BookingId::new);
    }

    public static BookingId bookingId(UUID raw) {
        return new BookingId(Uuid.uuid(raw));
    }

    public static BookingId bookingId() {
        return new BookingId(Uuid.randomUuid());
    }
}
