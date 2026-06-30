package org.pragmatica.example.ticketing.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class SeatStateTest {
    @Test
    void seatState_validName_returnsSeatState() {
        SeatState.seatState("  sold ").onFailure(cause -> fail(cause.message())).onSuccess(state -> assertThat(state).isEqualTo(SeatState.SOLD));
    }

    @Test
    void seatState_unknownName_returnsUnknown() {
        SeatState.seatState("reserved").onSuccess(state -> fail("Expected unknown state failure")).onFailure(cause -> assertThat(cause).isInstanceOf(SeatState.Error.Unknown.class));
    }

    @Test
    void dbValue_returnsLowercaseName() {
        assertThat(SeatState.AVAILABLE.dbValue()).isEqualTo("available");
        assertThat(SeatState.BLOCKED.dbValue()).isEqualTo("blocked");
        assertThat(SeatState.SOLD.dbValue()).isEqualTo("sold");
        assertThat(SeatState.WITHDRAWN.dbValue()).isEqualTo("withdrawn");
    }
}
