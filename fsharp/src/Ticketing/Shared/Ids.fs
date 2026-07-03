/// The eight UUID-backed identifier types. In Java each is its own ~50-line record with a nested
/// error hierarchy and three factories; the eight files are structural clones. F# keeps the eight
/// distinct types (an EventId still cannot be passed where a SeatId is expected) but writes the
/// shared shape exactly once.
namespace Ticketing.Shared

open System
open Pragmatica

/// Shared failure shape for the ids; the label keeps each type's message identical to its Java
/// counterpart ("Seat id must not be blank").
[<RequireQualifiedAccess>]
type IdError =
    | Blank of label: string
    | Malformed of label: string

    interface Cause with
        member this.Message =
            match this with
            | IdError.Blank label -> $"{label} must not be blank"
            | IdError.Malformed label -> $"{label} must be a valid UUID"

type BookingId = private BookingId of Guid
type CustomerId = private CustomerId of Guid
type EventId = private EventId of Guid
type EventScheduleId = private EventScheduleId of Guid
type HoldId = private HoldId of Guid
type ReceiptId = private ReceiptId of Guid
type SeatId = private SeatId of Guid
type TicketId = private TicketId of Guid

/// One definition of the id contract: parse-don't-validate from a raw string, wrap a raw Guid,
/// mint a fresh one.
module private Id =
    let parse (label: string) (wrap: Guid -> 'Id) (raw: string) : Result<'Id, Cause> =
        if not (Verify.Is.present raw) then
            Error(IdError.Blank label :> Cause)
        else
            match Guid.TryParse(raw.Trim()) with
            | true, value -> Ok(wrap value)
            | false, _ -> Error(IdError.Malformed label :> Cause)

[<RequireQualifiedAccess>]
module BookingId =
    let parse = Id.parse "Booking id" BookingId
    let ofGuid = BookingId
    let newId () = BookingId(Guid.NewGuid())
    let value (BookingId value) = value

[<RequireQualifiedAccess>]
module CustomerId =
    let parse = Id.parse "Customer id" CustomerId
    let ofGuid = CustomerId
    let newId () = CustomerId(Guid.NewGuid())
    let value (CustomerId value) = value

[<RequireQualifiedAccess>]
module EventId =
    let parse = Id.parse "Event id" EventId
    let ofGuid = EventId
    let newId () = EventId(Guid.NewGuid())
    let value (EventId value) = value

[<RequireQualifiedAccess>]
module EventScheduleId =
    let parse = Id.parse "Event schedule id" EventScheduleId
    let ofGuid = EventScheduleId
    let newId () = EventScheduleId(Guid.NewGuid())
    let value (EventScheduleId value) = value

[<RequireQualifiedAccess>]
module HoldId =
    let parse = Id.parse "Hold id" HoldId
    let ofGuid = HoldId
    let newId () = HoldId(Guid.NewGuid())
    let value (HoldId value) = value

[<RequireQualifiedAccess>]
module ReceiptId =
    let parse = Id.parse "Receipt id" ReceiptId
    let ofGuid = ReceiptId
    let newId () = ReceiptId(Guid.NewGuid())
    let value (ReceiptId value) = value

[<RequireQualifiedAccess>]
module SeatId =
    let parse = Id.parse "Seat id" SeatId
    let ofGuid = SeatId
    let newId () = SeatId(Guid.NewGuid())
    let value (SeatId value) = value

[<RequireQualifiedAccess>]
module TicketId =
    let parse = Id.parse "Ticket id" TicketId
    let ofGuid = TicketId
    let newId () = TicketId(Guid.NewGuid())
    let value (TicketId value) = value
