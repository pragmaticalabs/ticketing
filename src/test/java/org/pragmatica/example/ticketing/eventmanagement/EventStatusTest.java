package org.pragmatica.example.ticketing.eventmanagement;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class EventStatusTest {
    @Test
    void eventStatus_validName_returnsEventStatus() {
        EventStatus.eventStatus("  on_sale ")
                   .onFailure(cause -> fail(cause.message()))
                   .onSuccess(status -> assertThat(status).isEqualTo(EventStatus.ON_SALE));
    }

    @Test
    void eventStatus_unknownName_returnsUnknown() {
        EventStatus.eventStatus("postponed")
                   .onSuccess(_ -> fail("Expected unknown status failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(EventStatus.Error.Unknown.class));
    }

    @Test
    void eventStatus_nullName_returnsUnknown() {
        EventStatus.eventStatus(null)
                   .onSuccess(_ -> fail("Expected unknown status failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(EventStatus.Error.Unknown.class));
    }

    /// The round trip the store boundary relies on: every status must survive `dbValue()` and come back
    /// through `eventStatus(...)`, and the literals must match the `events.status` column comment.
    @Test
    void dbValue_returnsLowercaseName() {
        assertThat(EventStatus.DRAFT.dbValue()).isEqualTo("draft");
        assertThat(EventStatus.ON_SALE.dbValue()).isEqualTo("on_sale");
        assertThat(EventStatus.CANCELLED.dbValue()).isEqualTo("cancelled");
    }

    @Test
    void valueMapping_roundTripsEveryStatus() {
        Stream.of(EventStatus.values()).forEach(EventStatusTest::assertRoundTrip);
    }

    private static void assertRoundTrip(EventStatus status) {
        var mapping = EventStatus.valueMapping();

        mapping.lift()
               .apply(mapping.lower().apply(status))
               .onFailure(cause -> fail(cause.message()))
               .onSuccess(lifted -> assertThat(lifted).isEqualTo(status));
    }
}
