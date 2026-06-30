package org.pragmatica.example.ticketing.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.Locale;


/// Seat lifecycle state machine: a seat is AVAILABLE, may be BLOCKED (held back from sale), becomes
/// SOLD on a confirmed booking, or is WITHDRAWN. The authoritative transitions live in
/// eventmanagement's `seats` table (guarded SQL UPDATEs); this enum is the shared vocabulary every
/// subsystem's status strings must match. Parse-don't-validate: a raw state string is admitted only
/// through `seatState(...)`.
public enum SeatState {
    AVAILABLE,
    BLOCKED,
    SOLD,
    WITHDRAWN;
    public sealed interface Error extends Cause {
        record Unknown(String raw) implements Error {
            @Override
            public String message() {
                return "Unknown seat state: " + raw;
            }
        }

        static Error unknown(String raw) {
            return new Unknown(raw);
        }
    }
    public static Result<SeatState> seatState(String raw) {
        return Result.lift(Error.unknown(raw),
                           () -> SeatState.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
    }
    /// Persistence/wire form: the lowercase enum name, matching the `seats` and `seat_availability`
    /// status literals (`available`/`blocked`/`sold`/`withdrawn`).
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
