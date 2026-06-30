package org.pragmatica.example.ticketing.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class PercentTest {
    @Test
    void percent_positive_returnsPercent() {
        Percent.percent(110).onFailure(cause -> fail(cause.message())).onSuccess(p -> assertThat(p.value()).isEqualTo(110));
    }

    @Test
    void percent_zero_returnsNonPositive() {
        Percent.percent(0).onSuccess(p -> fail("Expected non-positive failure")).onFailure(cause -> assertThat(cause).isInstanceOf(Percent.Error.NonPositive.class));
    }

    @Test
    void percent_negative_returnsNonPositive() {
        Percent.percent(-5).onSuccess(p -> fail("Expected non-positive failure")).onFailure(cause -> assertThat(cause).isInstanceOf(Percent.Error.NonPositive.class));
    }
}
