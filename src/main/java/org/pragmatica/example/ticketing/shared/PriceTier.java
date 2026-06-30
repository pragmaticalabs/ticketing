package org.pragmatica.example.ticketing.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.Locale;


public enum PriceTier {
    PREMIUM,
    STANDARD,
    ECONOMY,
    ACCESSIBLE,
    RESTRICTED_VIEW;
    public sealed interface Error extends Cause {
        record Unknown(String raw) implements Error {
            @Override
            public String message() {
                return "Unknown price tier: " + raw;
            }
        }

        static Error unknown(String raw) {
            return new Unknown(raw);
        }
    }
    public static Result<PriceTier> priceTier(String raw) {
        return Result.lift(Error.unknown(raw),
                           () -> PriceTier.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
    }
}
