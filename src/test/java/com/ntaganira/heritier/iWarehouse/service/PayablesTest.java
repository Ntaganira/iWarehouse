package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is owed to a supplier per currency (ACC-08, ACC-09): settlements take the oldest items first with the RWF they were
 * booked at, so a payment knows the RWF of what it settles.
 */
class PayablesTest {

    @Test
    void settlementsTakeTheOldestItemsWithTheRwfTheyWereBookedAt() {
        Map<String, Payables.Open> open = Payables.open(List.of(
                line("2026-08-01", "USD", "3000", "3900000"),                  // at 1,300
                line("2026-09-01", "USD", "1500", "2025000"),                  // at 1,350
                line("2026-09-15", "RWF", "500000", "500000"),                 // a freight bill in RWF
                line("2026-09-20", "USD", "-1000", "-1300000")));              // paid: 1,000 of the first, at its 1,300

        Payables.Open usd = open.get("USD");
        assertThat(usd.getAmount()).isEqualByComparingTo("3500");
        assertThat(usd.getBase()).isEqualByComparingTo("4625000");              // 2,000 x 1,300 + 1,500 x 1,350
        assertThat(usd.items()).hasSize(2);
        assertThat(open.get("RWF").getAmount()).isEqualByComparingTo("500000");

        Payables.Settlement s = Payables.settle(usd, new BigDecimal("2500"));   // the rest of the first, 500 of the second
        assertThat(s.amount()).isEqualByComparingTo("2500");
        assertThat(s.base()).isEqualByComparingTo("3275000");                  // 2,600,000 + 500 x 1,350
        assertThat(usd.getAmount()).isEqualByComparingTo("3500");                // settling a copy changes nothing
    }

    @Test
    void aPartOfAnItemTakesItsShareOfTheRwfAndTheLastPartTheRest() {
        Payables.Open open = Payables.open(List.of(line("2026-09-01", "EUR", "3", "4000"))).get("EUR");   // 1,333.33... each
        Payables.Settlement first = Payables.settle(open, BigDecimal.ONE);
        assertThat(first.base()).isEqualByComparingTo("1333.33");

        Payables.Open after = Payables.open(List.of(line("2026-09-01", "EUR", "3", "4000"),
                line("2026-09-02", "EUR", "-1", "-1333.33"), line("2026-09-03", "EUR", "-1", "-1333.34"))).get("EUR");
        assertThat(after.getAmount()).isEqualByComparingTo("1");
        assertThat(after.getBase()).isEqualByComparingTo("1333.33");             // the three parts add up to 4,000
    }

    @Test
    void aCreditWithNothingToSettleWaitsAndNoMoreThanIsOwedIsPaid() {
        Payables.Open open = Payables.open(List.of(line("2026-09-01", "USD", "-200", "-260000"),     // a credit note first
                line("2026-09-10", "USD", "1000", "1350000"))).get("USD");
        assertThat(open.getAmount()).isEqualByComparingTo("800");
        assertThat(open.getBase()).isEqualByComparingTo("1080000");             // 800 of the bill at its 1,350
        assertThatThrownBy(() -> Payables.settle(open, new BigDecimal("800.01"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Payables.settle(open, new BigDecimal("800")).base()).isEqualByComparingTo("1080000");
    }

    private static Payables.Line line(String date, String currency, String amount, String base) {
        return new Payables.Line(LocalDate.parse(date), currency, new BigDecimal(amount), new BigDecimal(base));
    }
}
