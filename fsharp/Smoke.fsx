// Behavior smoke checks for the F# rendition, run against the built assembly:
//     dotnet build fsharp/Ticketing.fsproj && cd fsharp && dotnet fsi Smoke.fsx
// Covers the load-bearing semantics: applicative validation accumulating causes, Money
// invariants + HALF_UP scaling, the BuyTicket BER saga (happy path ordering, declined-payment
// and lost-confirm compensation), and CheckHold decay classification.
#r "bin/Debug/net10.0/Ticketing.dll"

open System
open Pragmatica
open Aether
open Ticketing.Shared
open Ticketing.Booking
open Ticketing.Booking.Purchase

let run (p: Promise<'T>) = Async.RunSynchronously p

let check name condition =
    if condition then printfn $"PASS  {name}"
    else printfn $"FAIL  {name}"; exit 1

// --- validation accumulates causes (Java Result.all semantics) ---
match BuyTicket.ValidBuy.parse { Customer = ""; Event = "not-a-uuid"; Seat = ""; Tier = "GOLD" } with
| Error cause ->
    let message = cause.Message
    check "ValidBuy accumulates all four causes" (
        message.Contains "Customer id must not be blank"
        && message.Contains "Event id must be a valid UUID"
        && message.Contains "Seat id must not be blank"
        && message.Contains "Unknown price tier: GOLD")
| Ok _ -> check "ValidBuy accumulates all four causes" false

// --- Money invariants ---
check "Money.parse rejects negative" (Result.isError (Money.parse "-1.00" "USD"))
check "Money.parse rejects sub-cent" (Result.isError (Money.parse "1.005" "USD"))

match Money.parse "25.00" "usd" with
| Ok money ->
    check "Money.parse normalizes currency + minor units"
        (Money.amountMinor money = 2500L && Money.render money = "25.00 USD")

    match Percent.parse 110L with
    | Ok percent ->
        let scaled = Money.scaledByPercent percent money
        check "scaledByPercent 110% of 25.00 = 27.50 (HALF_UP)" (Money.amountMinor scaled = 2750L)
    | Error _ -> check "Percent.parse 110" false
| Error cause -> check $"Money.parse 25.00 usd ({cause.Message})" false

check "Percent.parse rejects 0" (Result.isError (Percent.parse 0L))

// --- SeatState round-trip ---
check "SeatState parse/dbValue round-trip" (
    match SeatState.parse " sold " with
    | Ok state -> SeatState.dbValue state = "sold"
    | Error _ -> false)

// --- BuyTicket saga: happy path and BER compensation against a scripted store ---
type Calls() =
    member val Log: string list = [] with get, set
    member this.Record name = this.Log <- this.Log @ [ name ]

type FakeStore(calls: Calls, confirmSucceeds: bool) =
    interface BookingStore with
        member _.ClaimSeat(id, _, _, _) = calls.Record "claim"; Promise.success (Some { Id = id })
        member _.ConfirmReservation id =
            calls.Record "confirm"
            if confirmSucceeds then Promise.success (Some { Id = id }) else Promise.success None
        member _.ReleaseReservation id = calls.Record "release"; Promise.success (Some { Id = id })
        member _.CancelReservationBySeat _ = Promise.success None
        member _.ActiveBookingCount _ = calls.Record "count"; Promise.success 0L
        member _.InsertBooking(_, _, _, _, _, _) = calls.Record "insertBooking"; Promise.success ()
        member _.InsertPayment(_, _, _, _, _, _) = calls.Record "insertPayment"; Promise.success ()
        member _.InsertTicket(_, _, _) = calls.Record "insertTicket"; Promise.success ()
        member _.FindBooking _ = Promise.success None
        member _.CancelBooking _ = Promise.success None
        member _.InvalidateTicket _ = Promise.success ()
        member _.HoldDecay _ = Promise.success None
        member _.ExpireHolds() = Promise.success []

let gateway (approved: bool) =
    { new HttpClient with
        member _.PostJson<'Req, 'Resp>(path: string, _: 'Req) : Promise<'Resp> =
            match path with
            | "/authorize" ->
                Promise.success (box { BuyTicket.Approved = approved; BuyTicket.ReceiptId = string (Guid.NewGuid()) } :?> 'Resp)
            | "/void" -> Promise.success (box { BuyTicket.Status = "voided" } :?> 'Resp)
            | _ -> failwith $"unexpected path {path}" }

let notifier =
    { new NotificationSender with
        member _.Send _ = Promise.success () }

let quotePrice: Ticketing.Pricing.Quoting.QuotePrice.Execute =
    fun request ->
        Promise.success
            { Event = request.Event; Tier = request.Tier; AmountMinor = 2500L; Currency = "USD"; Version = 1L }

let saleStatus: Ticketing.EventManagement.Sales.SaleStatus.Execute =
    fun request -> Promise.success { Event = request.Event; OnSale = true; OnSaleAt = "2026-07-01T00:00:00Z" }

let request: BuyTicket.Request =
    { Customer = string (Guid.NewGuid())
      Event = string (Guid.NewGuid())
      Seat = string (Guid.NewGuid())
      Tier = "PREMIUM" }

// Happy path: seat sold, fact published, booking row written LAST.
let happyCalls = Calls()
let mutable published: Ticketing.Shared.Event.SeatSold list = []
let buyHappy =
    BuyTicket.buyTicket (FakeStore(happyCalls, true)) (gateway true) notifier quotePrice saleStatus
                        (fun fact -> published <- fact :: published; Promise.success ())

match run (buyHappy request) with
| Ok response ->
    check "happy path succeeds with USD amount" (response.AmountMinor = 2500L && response.Currency = "USD")
    check "happy path publishes SeatSold" (published.Length = 1 && published.Head.SeatId = request.Seat)
    check "bookings row is written last"
        (happyCalls.Log = [ "count"; "claim"; "confirm"; "insertTicket"; "insertPayment"; "insertBooking" ])
| Error cause -> check $"happy path ({cause.Message})" false

// Declined payment: BER releases the reservation and re-raises PaymentDeclined.
let declinedCalls = Calls()
let buyDeclined =
    BuyTicket.buyTicket (FakeStore(declinedCalls, true)) (gateway false) notifier quotePrice saleStatus
                        (fun _ -> Promise.success ())

match run (buyDeclined request) with
| Error cause ->
    check "declined payment re-raises PaymentDeclined" (cause.Message = "Payment was declined")
    check "declined payment released the reservation" (List.contains "release" declinedCalls.Log)
    check "declined payment never wrote records" (not (List.contains "insertBooking" declinedCalls.Log))
| Ok _ -> check "declined payment re-raises PaymentDeclined" false

// Lost confirm race: void + release, SeatUnavailable surfaces.
let raceCalls = Calls()
let buyRace =
    BuyTicket.buyTicket (FakeStore(raceCalls, false)) (gateway true) notifier quotePrice saleStatus
                        (fun _ -> Promise.success ())

match run (buyRace request) with
| Error cause ->
    check "lost confirm surfaces SeatUnavailable" (cause.Message = "Seat is no longer available")
    check "lost confirm compensates with release" (List.contains "release" raceCalls.Log)
| Ok _ -> check "lost confirm surfaces SeatUnavailable" false

// CheckHold decay labels.
open Ticketing.Booking.Hold

type HoldStore(row: HoldRow option) =
    interface BookingStore with
        member _.HoldDecay _ = Promise.success row
        member _.ClaimSeat(_, _, _, _) = Promise.success None
        member _.ConfirmReservation _ = Promise.success None
        member _.ReleaseReservation _ = Promise.success None
        member _.CancelReservationBySeat _ = Promise.success None
        member _.ActiveBookingCount _ = Promise.success 0L
        member _.InsertBooking(_, _, _, _, _, _) = Promise.success ()
        member _.InsertPayment(_, _, _, _, _, _) = Promise.success ()
        member _.InsertTicket(_, _, _) = Promise.success ()
        member _.FindBooking _ = Promise.success None
        member _.CancelBooking _ = Promise.success None
        member _.InvalidateTicket _ = Promise.success ()
        member _.ExpireHolds() = Promise.success []

let holdState row =
    let checkHold = CheckHold.checkHold (HoldStore row)
    match run (checkHold { Seat = string (Guid.NewGuid()) }) with
    | Ok response -> response.State
    | Error cause -> failwith cause.Message

check "hold decay NONE" (holdState None = "NONE")
check "hold decay EXPIRED" (holdState (Some { State = "held"; Expired = true; Stale = true }) = "EXPIRED")
check "hold decay STALE" (holdState (Some { State = "held"; Expired = false; Stale = true }) = "STALE")
check "hold decay FRESH" (holdState (Some { State = "held"; Expired = false; Stale = false }) = "FRESH")

printfn "All smoke checks passed."
