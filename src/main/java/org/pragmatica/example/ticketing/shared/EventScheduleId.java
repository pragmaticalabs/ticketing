package org.pragmatica.example.ticketing.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.vo.Uuid;

import java.util.UUID;


public record EventScheduleId(Uuid value) {
    public sealed interface Error extends Cause {
        record Blank() implements Error {
            @Override
            public String message() {
                return "Event schedule id must not be blank";
            }
        }

        record Malformed() implements Error {
            @Override
            public String message() {
                return "Event schedule id must be a valid UUID";
            }
        }

        static Error blank() {
            return new Blank();
        }

        static Error malformed() {
            return new Malformed();
        }
    }

    public static Result<EventScheduleId> eventScheduleId(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             Error.blank()).flatMap(present -> Uuid.uuid(present).mapError(_ -> Error.malformed()))
                            .map(EventScheduleId::new);
    }

    public static EventScheduleId eventScheduleId(UUID raw) {
        return new EventScheduleId(Uuid.uuid(raw));
    }

    public static EventScheduleId eventScheduleId() {
        return new EventScheduleId(Uuid.randomUuid());
    }
}
