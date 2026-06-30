package org.pragmatica.example.ticketing.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;


/// Non-negative monetary amount in minor units. A record's canonical constructor is unavoidably
/// public and cannot return a `Result`, so the non-negativity invariant is held at every
/// construction boundary instead: `money(...)` validates, `plus`/`sum` add non-negatives, and
/// `scaledByPercent` takes a positive `Percent`. No reachable path constructs a negative `Money`.
public record Money(long amountMinor, Currency currency) {
    public enum Currency {
        USD,
        EUR,
        GBP;
        public static Result<Currency> currency(String raw) {
            return Result.lift(Error.unknownCurrency(raw),
                               () -> Currency.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        }
    }

    public sealed interface Error extends Cause {
        record MalformedAmount(String raw) implements Error {
            @Override
            public String message() {
                return "Amount is malformed: " + raw;
            }
        }

        record NegativeAmount(long amountMinor) implements Error {
            @Override
            public String message() {
                return "Amount must not be negative: " + amountMinor;
            }
        }

        record UnknownCurrency(String raw) implements Error {
            @Override
            public String message() {
                return "Unknown currency: " + raw;
            }
        }

        record CurrencyMismatch(Currency left, Currency right) implements Error {
            @Override
            public String message() {
                return "Currency mismatch: " + left + " vs " + right;
            }
        }

        static Error malformedAmount(String raw) {
            return new MalformedAmount(raw);
        }

        static Error negativeAmount(long amountMinor) {
            return new NegativeAmount(amountMinor);
        }

        static Error unknownCurrency(String raw) {
            return new UnknownCurrency(raw);
        }

        static Error currencyMismatch(Currency left, Currency right) {
            return new CurrencyMismatch(left, right);
        }
    }

    public static Result<Money> money(String amount, String currency) {
        return Result.all(minorUnits(amount),
                          Currency.currency(currency))
                     .map(Money::new);
    }

    public Result<Money> plus(Money other) {
        return Verify.ensure(other.currency(),
                             Verify.Is::equalTo,
                             currency,
                             mismatch(other))
                     .map(_ -> sum(other));
    }

    // A positive `Percent` applied to a non-negative amount stays non-negative, so the result needs
    // no further check. This is the only way to scale a price: a raw long can no longer mint a
    // negative `Money`.
    public Money scaledByPercent(Percent percent) {
        return new Money(scaledMinor(percent.value()), currency);
    }

    public String render() {
        return BigDecimal.valueOf(amountMinor)
                         .movePointLeft(2)
                         .toPlainString() + " " + currency;
    }

    private static Result<Long> minorUnits(String amount) {
        return Number.parseBigDecimal(amount)
                     .mapError(_ -> Error.malformedAmount(amount))
                     .flatMap(Money::toMinorUnits)
                     .flatMap(Money::rejectNegative);
    }

    private static Result<Long> toMinorUnits(BigDecimal amount) {
        return Result.lift(Error.malformedAmount(amount.toPlainString()),
                           () -> amount.movePointRight(2)
                                       .longValueExact());
    }

    private static Result<Long> rejectNegative(long minor) {
        return Verify.ensure(minor, Verify.Is::nonNegative, Error.negativeAmount(minor));
    }

    private Cause mismatch(Money other) {
        return Error.currencyMismatch(currency, other.currency());
    }

    private Money sum(Money other) {
        return new Money(amountMinor + other.amountMinor(), currency);
    }

    private long scaledMinor(long percent) {
        return BigDecimal.valueOf(amountMinor)
                         .multiply(BigDecimal.valueOf(percent))
                         .divide(BigDecimal.valueOf(100),
                                 0,
                                 RoundingMode.HALF_UP)
                         .longValue();
    }
}
