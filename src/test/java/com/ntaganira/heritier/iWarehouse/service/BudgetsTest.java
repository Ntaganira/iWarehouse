package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : BudgetsTest.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The budget rules on worked examples (CMP 04, 10; Annex D; T.02, T.03, T.07, T.11): the months of a year, a
 *               variance and its tolerance, the share a revision changes, the Settings' days, a week's target, the margin.
 * </pre>
 */
class BudgetsTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void aYearIsSpreadOverTheMonthsInWholeFrancsAddingUpExactly() {
        BigDecimal[] months = Budgets.spread(bd("1000000"));
        assertThat(months[0]).isEqualByComparingTo("83333");
        assertThat(months[10]).isEqualByComparingTo("83333");
        assertThat(months[11]).isEqualByComparingTo("83337");                 // December takes the rest
        assertThat(Budgets.sum(months)).isEqualByComparingTo("1000000");
        assertThat(Budgets.toDate(months, 3)).isEqualByComparingTo("249999");
        assertThat(Budgets.toDate(months, 12)).isEqualByComparingTo("1000000");
    }

    @Test
    void aVarianceBeyondFivePercentEitherWayIsFlagged() {   // CMP 04, T.03
        Budgets.Variance over = new Budgets.Variance(bd("10000000"), bd("10600000"));
        assertThat(over.getAmount()).isEqualByComparingTo("600000");
        assertThat(over.getPercent()).isEqualByComparingTo("6.0");
        assertThat(over.flagged(bd("5"))).isTrue();
        Budgets.Variance under = new Budgets.Variance(bd("10000000"), bd("9600000"));
        assertThat(under.getPercent()).isEqualByComparingTo("-4.0");
        assertThat(under.flagged(bd("5"))).isFalse();
        assertThat(new Budgets.Variance(bd("10000000"), bd("9400000")).flagged(bd("5"))).isTrue();
        // Spent with nothing budgeted is flagged; nothing either way is not
        assertThat(new Budgets.Variance(bd("0"), bd("50000")).getPercent()).isNull();
        assertThat(new Budgets.Variance(bd("0"), bd("50000")).flagged(bd("5"))).isTrue();
        assertThat(new Budgets.Variance(bd("0"), bd("0")).flagged(bd("5"))).isFalse();
    }

    @Test
    void aRevisionIsMeasuredByTheShareItChangesTheLine() {   // T.02: the MD up to 15 %, the Board above
        assertThat(Budgets.revisionPercent(bd("10000000"), bd("11200000"))).isEqualByComparingTo("12.00");
        assertThat(Budgets.revisionPercent(bd("10000000"), bd("8000000"))).isEqualByComparingTo("20.00");
        assertThat(Budgets.revisionPercent(bd("0"), bd("500000"))).isEqualByComparingTo("100.00");
        assertThat(Budgets.revisionPercent(bd("0"), bd("0"))).isEqualByComparingTo("0");
    }

    @Test
    void theSettingsDaysAreDaysOfTheYearBeforeTheBudgets() {
        assertThat(Budgets.dayOf("11-30", 2026)).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(Budgets.dayOf("02-30", 2027)).isEqualTo(LocalDate.of(2027, 2, 28));     // past the month's end: its last day
        assertThat(Budgets.isMonthDay("12-31")).isTrue();
        assertThat(Budgets.isMonthDay("13-01")).isFalse();
        assertThat(Budgets.isMonthDay("02-29")).isFalse();
    }

    @Test
    void aWeeksTargetIsTheShareOfEachMonthsTargetItsDaysCover() {   // T.07
        Map<YearMonth, BigDecimal> monthly = Map.of(YearMonth.of(2026, 9), bd("30000000"), YearMonth.of(2026, 10), bd("31000000"));
        // 12 to 18 October: 7 of October's 31 days
        assertThat(Budgets.targetFor(monthly, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18))).isEqualByComparingTo("7000000");
        // 28 September to 4 October: 3 of September's 30 days and 4 of October's 31
        assertThat(Budgets.targetFor(monthly, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4))).isEqualByComparingTo("7000000");
        assertThat(Budgets.targetFor(monthly, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))).isEqualByComparingTo("31000000");
        assertThat(Budgets.targetFor(Map.of(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7))).isEqualByComparingTo("0");
    }

    @Test
    void theMarginAndTheShareAchieved() {   // Annex D
        assertThat(Budgets.marginPercent(bd("400000000"), bd("292000000"))).isEqualByComparingTo("27.0");
        assertThat(Budgets.marginPercent(bd("0"), bd("100"))).isNull();
        assertThat(Budgets.achieved(bd("14000000"), bd("15000000"))).isEqualByComparingTo("93.3");
        assertThat(Budgets.achieved(bd("14000000"), bd("0"))).isNull();
        assertThat(Budgets.proRata(bd("120000000"), 10)).isEqualByComparingTo("100000000");
    }
}
