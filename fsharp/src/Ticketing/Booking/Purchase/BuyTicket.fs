namespace Ticketing.Booking.Purchase

open System
open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.Shared.Event
open Ticketing.EventManagement.Sales
open Ticketing.Pricing.Quoting
open Ticketing.Booking

/// Use case: buy a ticket for a seat (the BER-saga centerpiece). Telescope leaf -- system
/// `ticketing` -> subsystem `booking` -> workflow `purchase` -> use case `buy-ticket`. One use
/// case, one `Request`/`Response` pair, one `execute` function.
///
/// Recovery classes:
///   - **design-out**: the seat claim is a single guarded `INSERT ... ON CONFLICT ... RETURNING`;
///     the loser of a contended seat fast-fails with SeatUnavailable -- no lock, no race.
///   - **BER** (backward error recovery / saga): a payment failure after the seat is claimed
///     releases the reservation; a failure after the payment is authorized voids the authorization
///     and releases the reservation. Compensation lives in dedicated helpers that re-raise the
///     original typed failure.
///   - **FER** (forward error recovery): the confirmation notification is best-effort -- a notify
///     failure never fails the buy.
///
/// Sale status and the authoritative price are read **synchronously** from the event-management and
/// pricing slices (injected as plain factory parameters); the payment gateway is an `@Http` resource
/// and notifications an `@Notify` resource.
[<Slice>]
module BuyTicket =
    type Request =
        { Customer: string
          Event: string
          Seat: string
          Tier: string }

    type Response =
        { Booking: string
          Ticket: string
          Seat: string
          Receipt: string
          AmountMinor: int64
          Currency: string }

    // Payment-gateway wire DTOs (plain records; the @Http client serializes/deserializes them as JSON).
    type AuthRequest =
        { AmountMinor: int64
          Currency: string
          Customer: string }

    type AuthResult = { Approved: bool; ReceiptId: string }

    type VoidRequest = { ReceiptId: string }

    type VoidResult = { Status: string }

    /// Closed set of buy failures. Each is a distinct case so route error-mapping can target it by
    /// simple name (see routes.toml).
    type BuyError =
        | SeatUnavailable
        | EventNotSelling
        | CustomerIneligible
        | PriceUnavailable
        | PaymentDeclined
        | PaymentProviderUnavailable
        | StoreUnavailable

        interface Cause with
            member this.Message =
                match this with
                | SeatUnavailable -> "Seat is no longer available"
                | EventNotSelling -> "Event is not currently selling"
                | CustomerIneligible -> "Customer has too many active bookings"
                | PriceUnavailable -> "No price is available for this event and tier"
                | PaymentDeclined -> "Payment was declined"
                | PaymentProviderUnavailable -> "Payment provider is unavailable"
                | StoreUnavailable -> "Booking store is unavailable"

    // Best-effort recipient derived from the customer id (the booking domain holds no email address).
    let customerMailbox (customerId: string) = $"{customerId}@customers.ticketing.example"

    /// Validated buy target. Raw request fields are parsed into value objects; all failures surface
    /// together via `and!`.
    type ValidBuy =
        { Customer: CustomerId
          Event: EventId
          Seat: SeatId
          Tier: PriceTier }

        member this.CustomerGuid = CustomerId.value this.Customer
        member this.EventGuid = EventId.value this.Event
        member this.SeatGuid = SeatId.value this.Seat
        member this.CustomerStr = string this.CustomerGuid
        member this.EventStr = string this.EventGuid
        member this.SeatStr = string this.SeatGuid
        member this.TierStr = PriceTier.name this.Tier

    module ValidBuy =
        let parse (request: Request) : Result<ValidBuy, Cause> =
            result {
                let! customer = CustomerId.parse request.Customer
                and! event = EventId.parse request.Event
                and! seat = SeatId.parse request.Seat
                and! tier = PriceTier.parse request.Tier
                return { Customer = customer; Event = event; Seat = seat; Tier = tier }
            }

    /// Growing-context stage: validated buy plus the authoritative price.
    type PricedBuy =
        { Buy: ValidBuy
          AmountMinor: int64
          Currency: string }

    /// Growing-context stage: priced buy plus the claimed reservation (the design-out seat claim).
    type ReservedBuy =
        { Priced: PricedBuy
          ReservationId: Guid }

        member this.Buy = this.Priced.Buy
        member this.AmountMinor = this.Priced.AmountMinor
        member this.Currency = this.Priced.Currency

    /// Growing-context stage: reserved buy plus the authorized payment receipt.
    type AuthorizedBuy =
        { Reserved: ReservedBuy
          ReceiptId: Guid }

        member this.Buy = this.Reserved.Buy
        member this.AmountMinor = this.Reserved.AmountMinor
        member this.Currency = this.Reserved.Currency
        member this.ReservationId = this.Reserved.ReservationId

    /// Terminal buy stage: the persisted booking and ticket, ready to notify, publish and respond.
    type Confirmation =
        { Authorized: AuthorizedBuy
          BookingId: Guid
          TicketId: Guid }

        member this.Response: Response =
            { Booking = string this.BookingId
              Ticket = string this.TicketId
              Seat = this.Authorized.Buy.SeatStr
              Receipt = string this.Authorized.ReceiptId
              AmountMinor = this.Authorized.AmountMinor
              Currency = this.Authorized.Currency }

        member this.Fact: SeatSold =
            { SeatId = this.Authorized.Buy.SeatStr
              EventId = this.Authorized.Buy.EventStr
              BookingId = string this.BookingId }

        member this.CustomerMailbox = customerMailbox this.Authorized.Buy.CustomerStr

        member this.EmailBody =
            $"Your ticket {this.TicketId} for seat {this.Authorized.Buy.SeatStr} is confirmed. "
            + $"Receipt: {this.Authorized.ReceiptId}"

    let private fromAddress = "tickets@ticketing.example"
    let private confirmationSubject = "Your ticket is confirmed"
    let private maxActiveBookings = 5L

    type Execute = Request -> Promise<Response>

    let buyTicket
        (store: BookingStore)
        (gateway: HttpClient)
        (notifier: NotificationSender)
        (quotePrice: QuotePrice.Execute)
        (saleStatus: SaleStatus.Execute)
        (seatSold: SeatSoldPublisher)
        : Execute =

        // Synchronous cross-slice read: any failure or a not-selling event surfaces as EventNotSelling.
        let readSaleStatus (valid: ValidBuy) : Promise<SaleStatus.Response> =
            saleStatus { Event = valid.EventStr } |> Promise.orFail EventNotSelling

        let countActiveBookings (valid: ValidBuy) : Promise<int64> =
            store.ActiveBookingCount valid.CustomerGuid
            |> Promise.orFail StoreUnavailable

        // JBCT pattern: Condition -- route on eligibility, no transformation.
        let eligibilityGate (valid: ValidBuy) (count: int64) : Promise<ValidBuy> =
            if count >= maxActiveBookings then
                Promise.fail CustomerIneligible
            else
                Promise.success valid

        // JBCT pattern: Condition -- route on the sale-status read, no transformation.
        let gate (valid: ValidBuy) (status: SaleStatus.Response) (count: int64) : Promise<ValidBuy> =
            if status.OnSale then
                eligibilityGate valid count
            else
                Promise.fail EventNotSelling

        // JBCT pattern: Fork-Join -- the synchronous sale-status read and the eligibility count are
        // independent and run in parallel over the immutable ValidBuy; the join gates the saga.
        let ensureSellingAndEligible (valid: ValidBuy) : Promise<ValidBuy> =
            Promise.all2 (readSaleStatus valid) (countActiveBookings valid)
            |> Promise.bind (fun (status, count) -> gate valid status count)

        // JBCT pattern: Leaf -- synchronous authoritative price read from the pricing slice.
        let priceBuy (valid: ValidBuy) : Promise<PricedBuy> =
            quotePrice { Event = valid.EventStr; Tier = valid.TierStr }
            |> Promise.orFail PriceUnavailable
            |> Promise.map (fun price ->
                { Buy = valid
                  AmountMinor = price.AmountMinor
                  Currency = price.Currency })

        // JBCT pattern: Leaf -- design-out seat claim; an empty projection means the seat is taken.
        let reserve (priced: PricedBuy) : Promise<ReservedBuy> =
            let reservationId = Guid.NewGuid()

            store.ClaimSeat(reservationId, priced.Buy.SeatGuid, priced.Buy.EventGuid, priced.Buy.CustomerGuid)
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (Promise.require SeatUnavailable)
            |> Promise.map (fun _ -> { Priced = priced; ReservationId = reservationId })

        let callGateway (reserved: ReservedBuy) : Promise<AuthResult> =
            gateway.PostJson<AuthRequest, AuthResult>(
                "/authorize",
                { AmountMinor = reserved.AmountMinor
                  Currency = reserved.Currency
                  Customer = reserved.Buy.CustomerStr }
            )
            |> Promise.orFail PaymentProviderUnavailable

        let acceptAuthorization (reserved: ReservedBuy) (result: AuthResult) : Promise<AuthorizedBuy> =
            ReceiptId.parse result.ReceiptId
            |> Result.orFail PaymentProviderUnavailable
            |> Result.map (fun receipt -> { Reserved = reserved; ReceiptId = ReceiptId.value receipt })
            |> Promise.fromResult

        // JBCT pattern: Condition -- approved continues, declined fails.
        let evaluateAuth (reserved: ReservedBuy) (result: AuthResult) : Promise<AuthorizedBuy> =
            if result.Approved then
                acceptAuthorization reserved result
            else
                Promise.fail PaymentDeclined

        let attemptAuthorize (reserved: ReservedBuy) : Promise<AuthorizedBuy> =
            callGateway reserved |> Promise.bind (evaluateAuth reserved)

        // BER compensation for the authorize step: release the reservation, then re-raise the cause.
        let releaseThenFail (reserved: ReservedBuy) (cause: Cause) : Promise<AuthorizedBuy> =
            store.ReleaseReservation reserved.ReservationId
            |> Promise.onResult (fun _ -> Promise.fail cause)

        let compensateAuthFailure (reserved: ReservedBuy) (result: Result<AuthorizedBuy, Cause>)
            : Promise<AuthorizedBuy> =
            match result with
            | Ok authorized -> Promise.success authorized
            | Error cause -> releaseThenFail reserved cause

        // JBCT pattern: Aspects -- wrap the authorization in BER compensation; any failure releases
        // the reservation and re-raises the original payment failure.
        let authorize (reserved: ReservedBuy) : Promise<AuthorizedBuy> =
            attemptAuthorize reserved
            |> Promise.onResult (compensateAuthFailure reserved)

        // JBCT pattern: Sequencer -- insert ticket, then payment, then the BOOKINGS row LAST. The
        // booking row is the only partial that counts in ActiveBookingCount and is readable by
        // CancelTicket, so writing it last makes any partial store failure precede it: the confirm-
        // step BER compensation (voidAndRelease) then fully reverses the saga with no orphaned
        // confirmed booking. (tickets/payments carry no FK to bookings, so the reorder is legal;
        // a stranded ticket/payment row is unreachable through every booking-keyed read.)
        let insertRecords (authorized: AuthorizedBuy) (bookingId: Guid) (ticketId: Guid) : Promise<unit> =
            let paymentId = Guid.NewGuid()

            store.InsertTicket(ticketId, bookingId, authorized.Buy.SeatGuid)
            |> Promise.bind (fun () ->
                store.InsertPayment(paymentId, bookingId, "authorized", authorized.ReceiptId,
                                    authorized.AmountMinor, authorized.Currency))
            |> Promise.bind (fun () ->
                store.InsertBooking(bookingId, authorized.ReservationId, authorized.Buy.SeatGuid,
                                    authorized.Buy.EventGuid, authorized.Buy.CustomerGuid, ticketId))
            |> Promise.orFail StoreUnavailable

        let persistAndIssue (authorized: AuthorizedBuy) : Promise<Confirmation> =
            let bookingId = BookingId.value (BookingId.newId ())
            let ticketId = TicketId.value (TicketId.newId ())

            store.ConfirmReservation authorized.ReservationId
            |> Promise.orFail StoreUnavailable
            |> Promise.bind (Promise.require SeatUnavailable)
            |> Promise.bind (fun _ -> insertRecords authorized bookingId ticketId)
            |> Promise.map (fun () ->
                { Authorized = authorized
                  BookingId = bookingId
                  TicketId = ticketId })

        // Best-effort gateway void (recovered to unit); the saga re-raises the original cause anyway.
        let voidAuthorization (authorized: AuthorizedBuy) : Promise<unit> =
            gateway.PostJson<VoidRequest, VoidResult>("/void", { ReceiptId = string authorized.ReceiptId })
            |> Promise.map ignore
            |> Promise.recover ignore

        let voidAndRelease (authorized: AuthorizedBuy) (cause: Cause) : Promise<Confirmation> =
            voidAuthorization authorized
            |> Promise.bind (fun () -> store.ReleaseReservation authorized.ReservationId)
            |> Promise.onResult (fun _ -> Promise.fail cause)

        // BER compensation for the confirm step: void the authorization and release the reservation,
        // then re-raise the original cause.
        let compensateConfirmFailure (authorized: AuthorizedBuy) (result: Result<Confirmation, Cause>)
            : Promise<Confirmation> =
            match result with
            | Ok confirmation -> Promise.success confirmation
            | Error cause -> voidAndRelease authorized cause

        let confirmationEmail (confirmation: Confirmation) : Notification =
            Email(fromAddress, [ confirmation.CustomerMailbox ], confirmationSubject, Text confirmation.EmailBody)

        // FER: a notification failure is swallowed so it never fails the buy.
        let sendConfirmation (confirmation: Confirmation) : Promise<unit> =
            notifier.Send(confirmationEmail confirmation) |> Promise.recover ignore

        let publishSold (confirmation: Confirmation) : Promise<Response> =
            seatSold confirmation.Fact
            |> Promise.map (fun () -> confirmation.Response)

        // JBCT pattern: Sequencer -- best-effort notify (FER), then publish SeatSold and respond.
        let notifyAndPublish (confirmation: Confirmation) : Promise<Response> =
            sendConfirmation confirmation
            |> Promise.bind (fun () -> publishSold confirmation)

        // JBCT pattern: Sequencer -- persist + issue under BER compensation, then notify + publish.
        let confirm (authorized: AuthorizedBuy) : Promise<Response> =
            persistAndIssue authorized
            |> Promise.onResult (compensateConfirmFailure authorized)
            |> Promise.bind notifyAndPublish

        // JBCT pattern: Sequencer -- validate -> gate (selling + eligibility) -> price -> reserve
        // -> authorize -> confirm. The BER compensation is declared inside the authorize and
        // confirm steps so each owns its own inverse.
        fun request ->
            ValidBuy.parse request
            |> Promise.fromResult
            |> Promise.bind ensureSellingAndEligible
            |> Promise.bind priceBuy
            |> Promise.bind reserve
            |> Promise.bind authorize
            |> Promise.bind confirm
