package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.CashRepeat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixed assets depreciated straight line over what is left of their life (C.08, U.12), and the rolling cash-flow forecast
 * (P.11), on worked examples.
 */
class AssetsAndCashFlowTest {

    private static final LocalDate AUG_20 = LocalDate.of(2026, 8, 20);

    @Test
    void anAssetIsDepreciatedStraightLineFromTheMonthAfterItWasAcquired() {
        // A forklift of 4,000,000, residual 400,000, 36 months: 100,000 a month from September
        assertThat(FixedAssets.defaultDepreciateFrom(AUG_20)).isEqualTo(LocalDate.of(2026, 9, 1));
        BigDecimal depreciable = new BigDecimal("3600000");
        LocalDate from = FixedAssets.defaultDepreciateFrom(AUG_20);
        assertThat(FixedAssets.monthly(AUG_20, from, 36, depreciable, BigDecimal.ZERO, YearMonth.of(2026, 8))).isEqualByComparingTo("0");
        assertThat(FixedAssets.monthly(AUG_20, from, 36, depreciable, BigDecimal.ZERO, YearMonth.of(2026, 9))).isEqualByComparingTo("100000");
        assertThat(FixedAssets.monthly(AUG_20, from, 36, depreciable, new BigDecimal("100000"), YearMonth.of(2026, 10)))
                .isEqualByComparingTo("100000");

        // Run every month, the 36 months add up to the depreciable amount exactly, and nothing is left after
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal awkward = new BigDecimal("1000000");                                          // 27,777.78 a month
        for (int m = 0; m < 36; m++) {
            total = total.add(FixedAssets.monthly(AUG_20, from, 36, awkward, total, YearMonth.of(2026, 9).plusMonths(m)));
        }
        assertThat(total).isEqualByComparingTo("1000000");
        assertThat(FixedAssets.monthly(AUG_20, from, 36, awkward, total, YearMonth.of(2029, 9))).isEqualByComparingTo("0");
    }

    @Test
    void aMonthNotRunSpreadsOverTheRestOfTheLifeAndALateRunTakesWhatIsLeft() {
        LocalDate from = FixedAssets.defaultDepreciateFrom(AUG_20);
        // The register starts in October: September's share spreads over the 35 months left
        BigDecimal oct = FixedAssets.monthly(AUG_20, from, 36, new BigDecimal("3600000"), BigDecimal.ZERO, YearMonth.of(2026, 10));
        assertThat(oct).isEqualByComparingTo("102857.14");
        // Past its life, what is left goes at once
        assertThat(FixedAssets.monthly(AUG_20, from, 36, new BigDecimal("3600000"), new BigDecimal("3500000"), YearMonth.of(2030, 1)))
                .isEqualByComparingTo("100000");
        assertThat(FixedAssets.monthsLeft(AUG_20, 36, YearMonth.of(2026, 7))).isEqualTo(36);    // before its life: the whole of it
    }

    @Test
    void anOpeningAssetTakesWhatIsLeftOverWhatIsLeftOfItsLife() {
        // Bought in January 2024 for 6,000,000 over 60 months; 1,800,000 depreciated before October 2026, from which it is depreciated
        LocalDate bought = LocalDate.of(2024, 1, 10);
        LocalDate from = LocalDate.of(2026, 10, 1);
        // Its life started February 2024: October 2026 is month 33, 28 months left (October included)
        assertThat(FixedAssets.monthsLeft(bought, 60, YearMonth.of(2026, 10))).isEqualTo(28);
        assertThat(FixedAssets.monthly(bought, from, 60, new BigDecimal("6000000"), new BigDecimal("1800000"), YearMonth.of(2026, 10)))
                .isEqualByComparingTo("150000");
        assertThat(FixedAssets.monthly(bought, from, 60, new BigDecimal("6000000"), new BigDecimal("1800000"), YearMonth.of(2026, 9)))
                .as("not before the month it is depreciated from").isEqualByComparingTo("0");
    }

    @Test
    void disposingOfAnAssetTakesItsBookValueOutAgainstWhatItFetched() {
        assertThat(FixedAssets.bookValue(new BigDecimal("4000000"), new BigDecimal("1200000"))).isEqualByComparingTo("2800000");
        assertThat(FixedAssets.disposalLoss(new BigDecimal("4000000"), new BigDecimal("1200000"), new BigDecimal("2500000")))
                .isEqualByComparingTo("300000");                                                // a loss
        assertThat(FixedAssets.disposalLoss(new BigDecimal("4000000"), new BigDecimal("1200000"), new BigDecimal("3000000")))
                .isEqualByComparingTo("-200000");                                               // a gain
        assertThat(FixedAssets.disposalLoss(new BigDecimal("4000000"), new BigDecimal("1200000"), null))
                .isEqualByComparingTo("2800000");                                               // scrapped
    }

    @Test
    void monthsAreRunInOrderOnceEnded() {
        LocalDate today = LocalDate.of(2026, 10, 16);
        assertThat(FixedAssets.nextRun(null, today)).isEqualTo(YearMonth.of(2026, 9));
        assertThat(FixedAssets.nextRun(YearMonth.of(2026, 9), today)).isEqualTo(YearMonth.of(2026, 10));
        assertThat(FixedAssets.mayRun(YearMonth.of(2026, 9), today)).isTrue();
        assertThat(FixedAssets.mayRun(YearMonth.of(2026, 10), today)).isFalse();
        assertThat(FixedAssets.mayRun(YearMonth.of(2026, 10), LocalDate.of(2026, 10, 31))).isTrue();     // on its last day
    }

    @Test
    void theForecastPutsEachFlowInItsWeekAndCarriesTheCashForward() {
        LocalDate start = LocalDate.of(2026, 10, 16);                                           // a Friday
        List<CashFlow.Flow> flows = List.of(
                new CashFlow.Flow(LocalDate.of(2026, 9, 30), CashFlow.Kind.RECEIVABLES, new BigDecimal("500000")),   // overdue: week 1
                new CashFlow.Flow(LocalDate.of(2026, 10, 22), CashFlow.Kind.RECEIVABLES, new BigDecimal("200000")),  // the 7th day: week 1
                new CashFlow.Flow(LocalDate.of(2026, 10, 23), CashFlow.Kind.PAYABLES, new BigDecimal("900000")),     // week 2
                new CashFlow.Flow(LocalDate.of(2026, 10, 31), CashFlow.Kind.PAYROLL, new BigDecimal("1500000")),     // week 3
                new CashFlow.Flow(LocalDate.of(2026, 11, 6), CashFlow.Kind.PLANNED_IN, new BigDecimal("50000")),     // week 4
                new CashFlow.Flow(LocalDate.of(2026, 11, 13), CashFlow.Kind.PLANNED_OUT, new BigDecimal("10000")));  // after 4 weeks
        List<CashFlow.Week> weeks = CashFlow.forecast(start, 4, new BigDecimal("2000000"), flows);

        assertThat(weeks).hasSize(4);
        assertThat(weeks.get(0).to()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(weeks.get(0).get(CashFlow.Kind.RECEIVABLES)).isEqualByComparingTo("700000");
        assertThat(weeks.get(0).getClosing()).isEqualByComparingTo("2700000");
        assertThat(weeks.get(1).opening()).isEqualByComparingTo("2700000");
        assertThat(weeks.get(1).getTotalOut()).isEqualByComparingTo("900000");
        assertThat(weeks.get(2).getClosing()).isEqualByComparingTo("300000");
        assertThat(weeks.get(3).getTotalIn()).isEqualByComparingTo("50000");
        assertThat(weeks.get(3).getClosing()).isEqualByComparingTo("350000");
        assertThat(weeks.stream().noneMatch(CashFlow.Week::isShort)).isTrue();

        List<CashFlow.Week> short_ = CashFlow.forecast(start, 2, new BigDecimal("100000"), flows);
        assertThat(short_.get(1).isShort()).isTrue();
        assertThat(short_.get(1).getClosing()).isEqualByComparingTo("-100000");
    }

    @Test
    void aPlannedItemRepeatsUntilItsLastDay() {
        LocalDate from = LocalDate.of(2026, 10, 16);
        LocalDate to = LocalDate.of(2027, 1, 14);
        assertThat(CashFlow.occurrences(LocalDate.of(2026, 10, 20), CashRepeat.NONE, null, from, to)).containsExactly(LocalDate.of(2026, 10, 20));
        assertThat(CashFlow.occurrences(LocalDate.of(2026, 10, 10), CashRepeat.NONE, null, from, to)).isEmpty();
        // Rent on the 5th of each month from September: October's is past, November to January in
        assertThat(CashFlow.occurrences(LocalDate.of(2026, 9, 5), CashRepeat.MONTHLY, null, from, to))
                .containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 12, 5), LocalDate.of(2027, 1, 5));
        // A weekly loan repayment until 6 November
        assertThat(CashFlow.occurrences(LocalDate.of(2026, 10, 16), CashRepeat.WEEKLY, LocalDate.of(2026, 11, 6), from, to))
                .containsExactly(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 6));
        assertThat(CashFlow.monthEnds(from, to)).containsExactly(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31));
    }

    @Test
    void openChargesKeepTheirDueDatesOncePaymentsSettledTheOldest() {
        List<Ageing.Due> due = Ageing.open(List.of(
                new Ageing.Entry(LocalDate.of(2026, 9, 1), new BigDecimal("100000"), BigDecimal.ZERO),
                new Ageing.Entry(LocalDate.of(2026, 9, 20), new BigDecimal("80000"), BigDecimal.ZERO),
                new Ageing.Entry(LocalDate.of(2026, 10, 1), BigDecimal.ZERO, new BigDecimal("130000")),
                new Ageing.Entry(LocalDate.of(2026, 10, 10), new BigDecimal("40000"), BigDecimal.ZERO, 15)), 30);
        assertThat(due).containsExactly(
                new Ageing.Due(LocalDate.of(2026, 10, 20), new BigDecimal("50000")),
                new Ageing.Due(LocalDate.of(2026, 11, 24), new BigDecimal("40000")));             // extended terms: 30 + 15 days
        assertThat(Ageing.open(List.of(new Ageing.Entry(LocalDate.of(2026, 9, 1), BigDecimal.ZERO, new BigDecimal("5000"))), 30)).isEmpty();
    }
}
