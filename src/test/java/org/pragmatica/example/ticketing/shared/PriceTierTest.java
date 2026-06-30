package org.pragmatica.example.ticketing.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class PriceTierTest {
    @Test
    void priceTier_validName_returnsPriceTier() {
        PriceTier.priceTier("  standard ").onFailure(cause -> fail(cause.message())).onSuccess(tier -> assertThat(tier).isEqualTo(PriceTier.STANDARD));
    }

    @Test
    void priceTier_unknownName_returnsUnknown() {
        PriceTier.priceTier("VIP").onSuccess(tier -> fail("Expected unknown tier failure")).onFailure(cause -> assertThat(cause).isInstanceOf(PriceTier.Error.Unknown.class));
    }
}
