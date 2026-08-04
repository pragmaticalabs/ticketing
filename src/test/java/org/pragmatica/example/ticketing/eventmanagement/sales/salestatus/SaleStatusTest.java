package org.pragmatica.example.ticketing.eventmanagement.sales.salestatus;

import java.util.UUID;

import org.pragmatica.example.ticketing.eventmanagement.FailingEventStore;
import org.pragmatica.example.ticketing.eventmanagement.InMemoryEventStore;
import org.pragmatica.example.ticketing.eventmanagement.sales.salestatus.SaleStatus.Request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class SaleStatusTest {
    private final InMemoryEventStore store = new InMemoryEventStore();
    private final SaleStatus slice = SaleStatus.saleStatus(store);

    private UUID eventWithOnSaleAt(String onSaleAt) {
        var id = UUID.randomUUID();

        store.insertEvent(id, "Wembley Arena", onSaleAt).await();

        return id;
    }

    @Test
    void execute_onSaleEvent_returnsOnSaleTrue() {
        var id = eventWithOnSaleAt("2026-07-01T19:00:00Z");

        store.openEvent(id).await();
        slice.execute(new Request(id.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(status -> assertThat(status.onSale()).isTrue());
    }

    @Test
    void execute_draftEvent_returnsOnSaleFalse() {
        var id = eventWithOnSaleAt("2026-07-01T19:00:00Z");

        slice.execute(new Request(id.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(status -> assertThat(status.onSale()).isFalse());
    }

    @Test
    void execute_cancelledEvent_returnsOnSaleFalse() {
        var id = eventWithOnSaleAt("2026-07-01T19:00:00Z");

        store.openEvent(id).await();
        store.cancelEvent(id).await();
        slice.execute(new Request(id.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(status -> assertThat(status.onSale()).isFalse());
    }

    @Test
    void execute_presentOnSaleAt_returnsTimestamp() {
        var id = eventWithOnSaleAt("2026-07-01T19:00:00Z");

        slice.execute(new Request(id.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(status -> assertThat(status.onSaleAt()).isEqualTo("2026-07-01T19:00:00Z"));
    }

    /// The (d) regression: `events.on_sale_at` is nullable, but the row mapped it to a plain `String`, so
    /// a SQL NULL travelled straight into the response. The store now contains it in an `Option` and the
    /// slice encodes absence explicitly.
    @Test
    void saleStatus_nullOnSaleAt_doesNotLeakNull() {
        var id = eventWithOnSaleAt(null);

        slice.execute(new Request(id.toString()))
             .await()
             .onFailure(cause -> fail(cause.message()))
             .onSuccess(status -> assertThat(status.onSaleAt()).isNotNull()
                                            .isEmpty());
    }

    @Test
    void execute_unknownEvent_returnsEventNotFound() {
        slice.execute(new Request(UUID.randomUUID().toString()))
             .await()
             .onSuccess(_ -> fail("Expected EventNotFound"))
             .onFailure(cause -> assertThat(cause.message()).contains("not found"));
    }

    @Test
    void execute_malformedId_returnsValidationFailure() {
        slice.execute(new Request("not-a-uuid"))
             .await()
             .onSuccess(_ -> fail("Expected validation failure"))
             .onFailure(cause -> assertThat(cause.message()).contains("valid UUID"));
    }

    @Test
    void execute_storeFails_returnsStoreUnavailable() {
        var failing = SaleStatus.saleStatus(FailingEventStore.allOperationsFail());

        failing.execute(new Request(UUID.randomUUID().toString()))
               .await()
               .onSuccess(_ -> fail("Expected StoreUnavailable"))
               .onFailure(cause -> assertThat(cause.message()).contains("unavailable"));
    }
}
