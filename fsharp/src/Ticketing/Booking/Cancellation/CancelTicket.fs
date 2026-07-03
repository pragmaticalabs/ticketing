namespace Ticketing.Booking.Cancellation

open System
open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.Shared.Event
open Ticketing.Booking

/// Use case: cancel a confirmed booking and refund it. Telescope leaf -- system `ticketing` ->
/// subsystem `booking` -> workflow `cancellation` -> use case `cancel-ticket`. One use case, one
/// `Request`/`Response` pair, one `execute` function.
///
/// Recovery class: **BER** -- ownership and cancellable state are checked first, the booking row and
/// reservation are cancelled, the payment is refunded at the gateway, the ticket is invalidated, and
/// the freed seat is published as a `SeatReleased` fact.
[<Slice>]
module CancelTicket =
    type Request = { Booking: string; Customer: string }

    type Response = { Booking: string; Receipt: string }

    // Payment-gateway wire DTOs (plain records; the @Http client serializes/deserializes them as JSON).
    type RefundRequest = { Booking: string }

    type RefundResult = { ReceiptId: string }

    /// Closed set of cancel failures. Each is a distinct case so route error-mapping can target it
    /// by simple name (see routes.toml).
    type CancelError =
        | BookingNotFound
        | NotOwner
        | AlreadyCancelled
        | RefundFailed
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | BookingNotFound -> "Booking not found"
                | NotOwner -> "Booking belongs to another customer"
                | AlreadyCancelled -> "Booking is already cancelled"
                | RefundFailed -> "Refund could not be completed"
                | StoreUnavailable -> "Booking store is unavailable"

    /// Validated cancel target.
    type ValidCancel =
        { Booking: BookingId
          Customer: CustomerId }

        member this.BookingGuid = BookingId.value this.Booking
        member this.CustomerGuid = CustomerId.value this.Customer
        member this.BookingStr = string this.BookingGuid

    module ValidCancel =
        let parse (request: Request) : Result<ValidCancel, Cause> =
            result {
                let! booking = BookingId.parse request.Booking
                and! customer = CustomerId.parse request.Customer
                return { Booking = booking; Customer = customer }
            }

    /// Growing-context stage: the validated cancel plus the loaded booking row.
    type LoadedBooking =
        { Valid: ValidCancel
          Booking: BookingRow }

        member this.BookingGuid = this.Valid.BookingGuid
        member this.BookingStr = this.Valid.BookingStr
        member this.SeatGuid = this.Booking.SeatId
        member this.SeatStr = string this.Booking.SeatId
        member this.EventStr = string this.Booking.EventId
        member this.TicketGuid = this.Booking.TicketId

    /// Terminal cancel stage: the loaded booking plus the refund receipt.
    type RefundedBooking =
        { Loaded: LoadedBooking
          Receipt: string }

        member this.TicketGuid = this.Loaded.TicketGuid
        member this.SeatStr = this.Loaded.SeatStr
        member this.EventStr = this.Loaded.EventStr
        member this.BookingStr = this.Loaded.BookingStr

    type Execute = Request -> Promise<Response>

    let cancelTicket (store: BookingStore) (gateway: HttpClient) (seatReleased: SeatReleasedPublisher) : Execute =
        let loadBooking (valid: ValidCancel) : Promise<LoadedBooking> =
            store.FindBooking valid.BookingGuid
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (Promise.require BookingNotFound)
            |> Promise.map (fun row -> { Valid = valid; Booking = row })

        let ensureNotCancelled (loaded: LoadedBooking) : Promise<LoadedBooking> =
            if loaded.Booking.Status = "cancelled" then
                Promise.fail AlreadyCancelled
            else
                Promise.success loaded

        // JBCT pattern: Condition (pure) -- ownership first, then cancellable state.
        let ensureCancellable (loaded: LoadedBooking) : Promise<LoadedBooking> =
            if loaded.Booking.CustomerId = loaded.Valid.CustomerGuid then
                ensureNotCancelled loaded
            else
                Promise.fail NotOwner

        let cancelBookingRow (loaded: LoadedBooking) : Promise<LoadedBooking> =
            store.CancelBooking loaded.BookingGuid
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (Promise.require AlreadyCancelled)
            |> Promise.map (fun _ -> loaded)

        let cancelReservation (loaded: LoadedBooking) : Promise<LoadedBooking> =
            store.CancelReservationBySeat loaded.SeatGuid
            |> Promise.orFail StoreUnavailable
            |> Promise.map (fun _ -> loaded)

        // Refund the booking at the gateway; a hard failure surfaces as RefundFailed.
        let refund (loaded: LoadedBooking) : Promise<RefundedBooking> =
            gateway.PostJson<RefundRequest, RefundResult>("/refund", { Booking = loaded.BookingStr })
            |> Promise.orFail RefundFailed
            |> Promise.map (fun result -> { Loaded = loaded; Receipt = result.ReceiptId })

        // JBCT pattern: Sequencer -- cancel booking row, free the reservation, then refund.
        let cancelAndRefund (loaded: LoadedBooking) : Promise<RefundedBooking> =
            cancelBookingRow loaded
            |> Promise.bind cancelReservation
            |> Promise.bind refund

        // JBCT pattern: Leaf -- invalidate the ticket, carrying the immutable stage forward.
        let invalidate (refunded: RefundedBooking) : Promise<RefundedBooking> =
            store.InvalidateTicket refunded.TicketGuid
            |> Promise.orFail StoreUnavailable
            |> Promise.map (fun () -> refunded)

        let publishReleased (refunded: RefundedBooking) : Promise<Response> =
            seatReleased
                { SeatId = refunded.SeatStr
                  EventId = refunded.EventStr }
            |> Promise.map (fun () ->
                { Booking = refunded.BookingStr
                  Receipt = refunded.Receipt })

        // JBCT pattern: Sequencer -- load -> ensure cancellable -> cancel + refund -> invalidate
        // -> publish SeatReleased. Recovery class: BER.
        fun request ->
            ValidCancel.parse request
            |> Promise.fromResult
            |> Promise.bind loadBooking
            |> Promise.bind ensureCancellable
            |> Promise.bind cancelAndRefund
            |> Promise.bind invalidate
            |> Promise.bind publishReleased
