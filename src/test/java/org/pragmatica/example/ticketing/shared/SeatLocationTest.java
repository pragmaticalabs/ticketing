package org.pragmatica.example.ticketing.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class SeatLocationTest {
    @Test
    void seatLocation_validInput_returnsSeatLocation() {
        SeatLocation.seatLocation("A", "12", 7).onFailure(cause -> fail(cause.message())).onSuccess(seat -> {
            assertThat(seat.section()).isEqualTo("A");
            assertThat(seat.row()).isEqualTo("12");
            assertThat(seat.number()).isEqualTo(7);
        });
    }

    @Test
    void seatLocation_blankSection_returnsBlankSection() {
        SeatLocation.seatLocation("", "12", 7).onSuccess(seat -> fail("Expected blank section failure")).onFailure(cause -> assertThat(cause.message()).contains("Seat section must not be blank"));
    }

    @Test
    void seatLocation_blankRow_returnsBlankRow() {
        SeatLocation.seatLocation("A", "", 7).onSuccess(seat -> fail("Expected blank row failure")).onFailure(cause -> assertThat(cause.message()).contains("Seat row must not be blank"));
    }

    @Test
    void seatLocation_nonPositiveNumber_returnsNonPositiveNumber() {
        SeatLocation.seatLocation("A", "12", 0).onSuccess(seat -> fail("Expected non-positive number failure")).onFailure(cause -> assertThat(cause.message()).contains("Seat number must be positive"));
    }
}
