namespace Ticketing.Shared

open Pragmatica

[<RequireQualifiedAccess>]
type SeatLocationError =
    | BlankSection
    | BlankRow
    | NonPositiveNumber

    interface Cause with
        member this.Message =
            match this with
            | SeatLocationError.BlankSection -> "Seat section must not be blank"
            | SeatLocationError.BlankRow -> "Seat row must not be blank"
            | SeatLocationError.NonPositiveNumber -> "Seat number must be positive"

type SeatLocation =
    { Section: string
      Row: string
      Number: int }

[<RequireQualifiedAccess>]
module SeatLocation =
    let parse (section: string) (row: string) (number: int) : Result<SeatLocation, Cause> =
        result {
            let! validSection = section |> Verify.ensure Verify.Is.present SeatLocationError.BlankSection
            and! validRow = row |> Verify.ensure Verify.Is.present SeatLocationError.BlankRow
            and! validNumber = number |> Verify.ensure (fun n -> n > 0) SeatLocationError.NonPositiveNumber
            return { Section = validSection; Row = validRow; Number = validNumber }
        }
