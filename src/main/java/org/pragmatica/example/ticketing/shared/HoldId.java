package org.pragmatica.example.ticketing.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.vo.Uuid;

import java.util.UUID;


public record HoldId(Uuid value) {
    public sealed interface Error extends Cause {
        record Blank() implements Error {
            @Override
            public String message() {
                return "Hold id must not be blank";
            }
        }

        record Malformed() implements Error {
            @Override
            public String message() {
                return "Hold id must be a valid UUID";
            }
        }

        static Error blank() {
            return new Blank();
        }

        static Error malformed() {
            return new Malformed();
        }
    }

    public static Result<HoldId> holdId(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             Error.blank()).flatMap(present -> Uuid.uuid(present).mapError(_ -> Error.malformed()))
                            .map(HoldId::new);
    }

    public static HoldId holdId(UUID raw) {
        return new HoldId(Uuid.uuid(raw));
    }

    public static HoldId holdId() {
        return new HoldId(Uuid.randomUuid());
    }
}
