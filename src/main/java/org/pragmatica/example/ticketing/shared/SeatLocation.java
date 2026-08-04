package org.pragmatica.example.ticketing.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


public record SeatLocation(String section, String row, int number) {
    public sealed interface Error extends Cause {
        /// Blank text fields -- unparseable input. `add-seat` re-declares every constant here as its own
        /// HTTP 400 cause, which is why the group is named for that routing outcome: a refusal that is
        /// *not* a 400 must not be added to it.
        enum Invalid implements Error {
            BLANK_SECTION("Seat section must not be blank"),
            BLANK_ROW("Seat row must not be blank");
            private final String message;
            Invalid(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        /// A well-formed `int` outside the admissible domain. `add-seat` re-declares it as HTTP 422, so
        /// it is a separate group from [Invalid] -- sharing one enum would collapse two statuses into
        /// one type name and make them indistinguishable to the caller.
        enum Unacceptable implements Error {
            NON_POSITIVE_NUMBER("Seat number must be positive");
            private final String message;
            Unacceptable(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        static Error blankSection() {
            return Invalid.BLANK_SECTION;
        }

        static Error blankRow() {
            return Invalid.BLANK_ROW;
        }

        static Error nonPositiveNumber() {
            return Unacceptable.NON_POSITIVE_NUMBER;
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
