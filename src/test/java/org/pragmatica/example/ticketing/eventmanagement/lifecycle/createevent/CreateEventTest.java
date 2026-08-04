package org.pragmatica.example.ticketing.eventmanagement.lifecycle.createevent;

import org.pragmatica.example.ticketing.eventmanagement.FailingEventStore;
import org.pragmatica.example.ticketing.eventmanagement.InMemoryEventStore;
import org.pragmatica.example.ticketing.eventmanagement.lifecycle.createevent.CreateEvent.Request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class CreateEventTest {
    private final InMemoryEventStore store = new InMemoryEventStore();
    private final CreateEvent slice = CreateEvent.createEvent(store);

    @Test
    void createEvent_validRequest_returnsEvent() {
        slice.execute(new Request("Wembley Arena", "2026-07-01T19:00:00Z"))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(response -> assertThat(response.event()).isNotBlank());
    }

    @Test
    void createEvent_blankVenue_returnsBlankVenue() {
        slice.execute(new Request("   ", "2026-07-01T19:00:00Z"))
             .await()
             .onSuccess(_ -> fail("Expected BlankVenue"))
             .onFailure(cause -> assertThat(cause.message()).contains("Venue"));
    }

    @Test
    void createEvent_malformedOnSaleAt_returnsMalformedOnSaleAt() {
        slice.execute(new Request("Wembley Arena", "not-a-timestamp"))
             .await()
             .onSuccess(_ -> fail("Expected MalformedOnSaleAt"))
             .onFailure(cause -> assertThat(cause.message()).contains("ISO-8601"));
    }

    @Test
    void createEvent_storeFails_returnsStoreUnavailable() {
        var failing = CreateEvent.createEvent(FailingEventStore.allOperationsFail());

        failing.execute(new Request("Wembley Arena", "2026-07-01T19:00:00Z"))
               .await()
               .onSuccess(_ -> fail("Expected StoreUnavailable"))
               .onFailure(cause -> assertThat(cause.message()).contains("unavailable"));
    }
}
