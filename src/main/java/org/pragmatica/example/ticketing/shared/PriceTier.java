package org.pragmatica.example.ticketing.shared;

import java.util.Locale;

import org.pragmatica.aether.slice.mapping.ValueMapping;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;


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
    /// Store-boundary descriptor: `pg-codegen` lowers a `PriceTier` column to its uppercase enum name
    /// and lifts a raw `tier` column back through `priceTier(...)`, so a corrupt tier value fails the
    /// row decode instead of yielding an invalid tier.
    public static ValueMapping<PriceTier, String> valueMapping() {
        return ValueMapping.of(PriceTier::name, PriceTier::priceTier);
    }
}
