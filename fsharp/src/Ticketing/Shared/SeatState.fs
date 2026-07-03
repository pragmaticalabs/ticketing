namespace Ticketing.Shared

open Pragmatica

[<RequireQualifiedAccess>]
type SeatStateError =
    | Unknown of raw: string

    interface Cause with
        member this.Message =
            match this with
            | SeatStateError.Unknown raw -> $"Unknown seat state: {raw}"

/// Seat lifecycle state machine: a seat is Available, may be Blocked (held back from sale), becomes
/// Sold on a confirmed booking, or is Withdrawn. The authoritative transitions live in
/// eventmanagement's `seats` table (guarded SQL UPDATEs); this union is the shared vocabulary every
/// subsystem's status strings must match. Parse-don't-validate: a raw state string is admitted only
/// through `SeatState.parse`.
[<RequireQualifiedAccess>]
type SeatState =
    | Available
    | Blocked
    | Sold
    | Withdrawn

[<RequireQualifiedAccess>]
module SeatState =
    let parse (raw: string) : Result<SeatState, Cause> =
        match raw.Trim().ToUpperInvariant() with
        | "AVAILABLE" -> Ok SeatState.Available
        | "BLOCKED" -> Ok SeatState.Blocked
        | "SOLD" -> Ok SeatState.Sold
        | "WITHDRAWN" -> Ok SeatState.Withdrawn
        | _ -> Error(SeatStateError.Unknown raw :> Cause)

    /// Persistence/wire form: the lowercase name, matching the `seats` and `seat_availability`
    /// status literals (`available`/`blocked`/`sold`/`withdrawn`).
    let dbValue (state: SeatState) =
        match state with
        | SeatState.Available -> "available"
        | SeatState.Blocked -> "blocked"
        | SeatState.Sold -> "sold"
        | SeatState.Withdrawn -> "withdrawn"
