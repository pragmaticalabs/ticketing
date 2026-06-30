package org.pragmatica.example.ticketing.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


public record SeatLocation(String section, String row, int number) {
    public sealed interface Error extends Cause {
        record BlankSection() implements Error {
            @Override
            public String message() {
                return "Seat section must not be blank";
            }
        }

        record BlankRow() implements Error {
            @Override
            public String message() {
                return "Seat row must not be blank";
            }
        }

        record NonPositiveNumber() implements Error {
            @Override
            public String message() {
                return "Seat number must be positive";
            }
        }

        static Error blankSection() {
            return new BlankSection();
        }

        static Error blankRow() {
            return new BlankRow();
        }

        static Error nonPositiveNumber() {
            return new NonPositiveNumber();
        }
    }

    public static Result<SeatLocation> seatLocation(String section, String row, int number) {
        return Result.all(Verify.ensure(section,
                                        Verify.Is::present,
                                        Error.blankSection()),
                          Verify.ensure(row,
                                        Verify.Is::present,
                                        Error.blankRow()),
                          Verify.ensure(number,
                                        Verify.Is::positive,
                                        Error.nonPositiveNumber()))
                     .map(SeatLocation::new);
    }
}
