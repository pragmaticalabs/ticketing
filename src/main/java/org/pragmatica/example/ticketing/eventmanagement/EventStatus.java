package org.pragmatica.example.ticketing.eventmanagement;

import java.util.Locale;

import org.pragmatica.aether.slice.mapping.ValueMapping;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;


/// Event lifecycle state machine: an event is DRAFT until it is opened for sale (ON_SALE), and may be
/// CANCELLED from either of those states. CANCELLED is terminal -- no transition leaves it, and no
/// capacity may be added to it. The authoritative transitions live in eventmanagement's `events` table
/// (guarded SQL UPDATEs); this enum is the vocabulary every `status` literal must match.
/// Parse-don't-validate: a raw status string is admitted only through `eventStatus(...)`, so no slice
/// ever compares a bare string literal.
///
/// Refusals of a guarded lifecycle transition are **not** declared here. They read as one vocabulary --
/// "the event is cancelled", "a concurrent change won the race" -- but each routed slice can only produce
/// the subset its own guard admits: `open-event` can refuse for either reason, `add-seat` only for a
/// cancelled event, `cancel-event` only for a race (a cancelled event is its success). Declaring one
/// shared sum here would widen all three signatures to refusals they cannot return, and the slice
/// processor discovers mappable `Cause` types only inside a routed slice's own package, so such a sum
/// could never be mapped to HTTP 409 at all. Each slice therefore declares its own narrower variants in
/// its own `*Error` hierarchy, and maps them in its own `routes.toml`.
public enum EventStatus {
    DRAFT,
    ON_SALE,
    CANCELLED;
    public sealed interface Error extends Cause {
        record Unknown(String raw) implements Error {
            @Override
            public String message() {
                return "Unknown event status: " + raw;
            }
        }

        static Error unknown(String raw) {
            return new Unknown(raw);
        }
    }
    public static Result<EventStatus> eventStatus(String raw) {
        return Result.lift(Error.unknown(raw),
                           () -> EventStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
    }
    /// Persistence/wire form: the lowercase enum name, matching the `events.status` literals
    /// (`draft`/`on_sale`/`cancelled`).
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
    /// Store-boundary descriptor: `pg-codegen` lowers an `EventStatus` column to its `dbValue()` string
    /// and lifts a raw column back through `eventStatus(...)`, so a corrupt `status` value fails the row
    /// decode instead of yielding an invalid status.
    public static ValueMapping<EventStatus, String> valueMapping() {
        return ValueMapping.of(EventStatus::dbValue, EventStatus::eventStatus);
    }
}
