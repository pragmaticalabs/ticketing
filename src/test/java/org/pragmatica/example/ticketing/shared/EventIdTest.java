package org.pragmatica.example.ticketing.shared;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class EventIdTest {
    @Test
    void eventId_validUuid_returnsEventId() {
        var raw = UUID.randomUUID().toString();

        EventId.eventId(raw).onFailure(cause -> fail(cause.message())).onSuccess(id -> assertThat(id.value().value().toString()).isEqualTo(raw));
    }

    @Test
    void eventId_blank_returnsBlank() {
        EventId.eventId("   ").onSuccess(id -> fail("Expected blank failure")).onFailure(cause -> assertThat(cause).isInstanceOf(EventId.Error.Blank.class));
    }

    @Test
    void eventId_malformed_returnsMalformed() {
        EventId.eventId("not-a-uuid").onSuccess(id -> fail("Expected malformed failure")).onFailure(cause -> assertThat(cause).isInstanceOf(EventId.Error.Malformed.class));
    }
}
