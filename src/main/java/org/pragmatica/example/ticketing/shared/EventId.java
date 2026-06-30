package org.pragmatica.example.ticketing.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.vo.Uuid;

import java.util.UUID;


public record EventId(Uuid value) {
    public sealed interface Error extends Cause {
        record Blank() implements Error {
            @Override
            public String message() {
                return "Event id must not be blank";
            }
        }

        record Malformed() implements Error {
            @Override
            public String message() {
                return "Event id must be a valid UUID";
            }
        }

        static Error blank() {
            return new Blank();
        }

        static Error malformed() {
            return new Malformed();
        }
    }

    public static Result<EventId> eventId(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             Error.blank()).flatMap(present -> Uuid.uuid(present).mapError(_ -> Error.malformed()))
                            .map(EventId::new);
    }

    public static EventId eventId(UUID raw) {
        return new EventId(Uuid.uuid(raw));
    }

    public static EventId eventId() {
        return new EventId(Uuid.randomUuid());
    }
}
