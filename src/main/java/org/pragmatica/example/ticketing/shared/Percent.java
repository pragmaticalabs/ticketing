package org.pragmatica.example.ticketing.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// Demand-scaling percentage (110 = +10%, 90 = -10%). Strictly positive by construction: scaling a
/// non-negative price by a positive percentage can never drive it to zero or below, so `Money`
/// stays non-negative without a further check. Parse-don't-validate: the raw request `long` is
/// admitted only through `percent(...)`.
public record Percent(long value) {
    public sealed interface Error extends Cause {
        record NonPositive(long value) implements Error {
            @Override
            public String message() {
                return "Percent must be positive: " + value;
            }
        }

        static Error nonPositive(long value) {
            return new NonPositive(value);
        }
    }

    public static Result<Percent> percent(long value) {
        return Verify.ensure(value,
                             Verify.Is::positive,
                             Error.nonPositive(value))
                     .map(Percent::new);
    }
}
