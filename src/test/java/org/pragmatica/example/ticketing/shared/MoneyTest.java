package org.pragmatica.example.ticketing.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


class MoneyTest {
    @Test
    void money_validAmount_returnsMoney() {
        Money.money("50.00", "USD").onFailure(cause -> fail(cause.message())).onSuccess(m -> {
            assertThat(m.amountMinor()).isEqualTo(5000);
            assertThat(m.currency()).isEqualTo(Money.Currency.USD);
        });
    }

    @Test
    void money_malformedAmount_returnsMalformedAmount() {
        Money.money("not-a-number", "USD").onSuccess(m -> fail("Expected malformed amount")).onFailure(cause -> assertThat(cause.message()).contains("Amount is malformed"));
    }

    @Test
    void money_negativeAmount_returnsNegativeAmount() {
        Money.money("-1.00", "USD").onSuccess(m -> fail("Expected negative amount")).onFailure(cause -> assertThat(cause.message()).contains("Amount must not be negative"));
    }

    @Test
    void money_unknownCurrency_returnsUnknownCurrency() {
        Money.money("50.00", "XYZ").onSuccess(m -> fail("Expected unknown currency")).onFailure(cause -> assertThat(cause.message()).contains("Unknown currency"));
    }

    @Test
    void plus_sameCurrency_sumsAmounts() {
        Money.money("50.00", "USD").flatMap(a -> Money.money("10.00", "USD").flatMap(a::plus)).onFailure(cause -> fail(cause.message())).onSuccess(sum -> assertThat(sum.amountMinor()).isEqualTo(6000));
    }

    @Test
    void plus_differentCurrency_returnsCurrencyMismatch() {
        Money.money("50.00", "USD").flatMap(a -> Money.money("10.00", "EUR").flatMap(a::plus)).onSuccess(sum -> fail("Expected currency mismatch")).onFailure(cause -> assertThat(cause.message()).contains("Currency mismatch"));
    }

    @Test
    void scaledByPercent_scalesAmount() {
        Money.money("50.00", "USD").onFailure(cause -> fail(cause.message())).onSuccess(money -> {
            Percent.percent(110).onFailure(cause -> fail(cause.message())).onSuccess(percent -> assertThat(money.scaledByPercent(percent).amountMinor()).isEqualTo(5500));
            Percent.percent(50).onFailure(cause -> fail(cause.message())).onSuccess(percent -> assertThat(money.scaledByPercent(percent).amountMinor()).isEqualTo(2500));
        });
    }

    @Test
    void render_validMoney_formatsCorrectly() {
        Money.money("50.00", "USD").onFailure(cause -> fail(cause.message())).onSuccess(m -> assertThat(m.render()).isEqualTo("50.00 USD"));
    }
}
