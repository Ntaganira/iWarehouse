package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Month-end revaluation of open foreign balances (ACC-08): what is owed at the month's last rate, the gain or loss against
 * what it was booked at, and which months can be revalued.
 */
class FxRevaluationsTest {

    @Test
    void whatIsOwedIsWorthItsAmountAtTheRateAndBookedLessThatIsTheGainOrLoss() {
        // 62.84 USD booked at 91,740.20; the rate fell to 1,455.123456: 91,439.96, a gain of 300.24
        FxRevaluations.Revalued fell = FxRevaluations.revalue(new BigDecimal("62.84"), new BigDecimal("91740.20"), new BigDecimal("1455.123456"));
        assertThat(fell.revalued()).isEqualByComparingTo("91439.96");
        assertThat(fell.gainLoss()).isEqualByComparingTo("300.24");

        // The rate rose to 1,480: 93,003.20, a loss of 1,263
        FxRevaluations.Revalued rose = FxRevaluations.revalue(new BigDecimal("62.84"), new BigDecimal("91740.20"), new BigDecimal("1480"));
        assertThat(rose.revalued()).isEqualByComparingTo("93003.20");
        assertThat(rose.gainLoss()).isEqualByComparingTo("-1263.00");
    }

    @Test
    void aDebitBalanceMovesTheOtherWayAndARoundingLeftoverIsCleared() {
        // Invoiced before it was received: GRNI is a debit (owed -62.84 USD); the rate fell, so it is a loss there,
        // the gain on the payable the invoice made
        FxRevaluations.Revalued grni = FxRevaluations.revalue(new BigDecimal("-62.84"), new BigDecimal("-91740.20"), new BigDecimal("1455"));
        assertThat(grni.revalued()).isEqualByComparingTo("-91432.20");
        assertThat(grni.gainLoss()).isEqualByComparingTo("-308.00");
        FxRevaluations.Revalued payable = FxRevaluations.revalue(new BigDecimal("62.84"), new BigDecimal("91740.20"), new BigDecimal("1455"));
        assertThat(payable.gainLoss().add(grni.gainLoss())).isEqualByComparingTo("0");

        // Nothing owed in the currency, a cent left in RWF: revalued to nothing
        FxRevaluations.Revalued leftover = FxRevaluations.revalue(BigDecimal.ZERO, new BigDecimal("0.01"), new BigDecimal("1455"));
        assertThat(leftover.revalued()).isEqualByComparingTo("0");
        assertThat(leftover.gainLoss()).isEqualByComparingTo("0.01");
    }

    @Test
    void aMonthCanBeRevaluedOnceItHasEndedFromTheLedgersFirstMonth() {
        LocalDate today = LocalDate.of(2026, 10, 9);
        assertThat(FxRevaluations.months(LocalDate.of(2026, 8, 14), today, List.of()))
                .containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 8));          // newest first; October has not ended
        assertThat(FxRevaluations.months(LocalDate.of(2026, 8, 14), today, List.of(LocalDate.of(2026, 9, 30))))
                .containsExactly(YearMonth.of(2026, 8));
        assertThat(FxRevaluations.months(LocalDate.of(2026, 10, 1), today, List.of())).isEmpty();
        assertThat(FxRevaluations.months(null, today, List.of())).isEmpty();             // no journal yet

        assertThat(FxRevaluations.hasEnded(YearMonth.of(2026, 9), LocalDate.of(2026, 10, 1))).isTrue();
        assertThat(FxRevaluations.hasEnded(YearMonth.of(2026, 9), LocalDate.of(2026, 9, 30))).isFalse();
    }
}
