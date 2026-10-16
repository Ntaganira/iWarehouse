package com.ntaganira.heritier.iWarehouse.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : InventoryKpisTest.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The inventory KPIs against the policy's targets (Inventory Policy 19, Annex D) and the report periods
 *               (Policy 18), on worked examples.
 * </pre>
 */
class InventoryKpisTest {

    private static final InventoryKpis.Targets POLICY = new InventoryKpis.Targets(bd("99"), bd("0.5"), bd("1"), null, bd("98"), bd("100"),
            bd("100"), bd("3"));

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static InventoryKpis.Inputs inputs(int matched, int expected, String lost, String damaged, String opening, String closing, String cogs,
                                               int days) {
        return new InventoryKpis.Inputs(matched, expected, bd(lost), bd(damaged), bd(opening), bd(closing), bd(cogs), days, 50, 1, 22, 21, 4, 3,
                10, bd("25.5"));
    }

    @Test
    void aMonthAgainstThePolicy() {
        // 198 of 200 units in place; 40,000 lost and 120,000 damaged on an average stock of 10,000,000; 3,000,000 sold at cost in 30 days
        List<InventoryKpis.Kpi> kpis = InventoryKpis.of(inputs(198, 200, "40000", "120000", "9500000", "10500000", "3000000", 30), POLICY);

        InventoryKpis.Kpi accuracy = InventoryKpis.find(kpis, InventoryKpis.Code.ACCURACY);
        assertThat(accuracy.value()).isEqualByComparingTo("99.0");
        assertThat(accuracy.state()).isEqualTo(InventoryKpis.State.MET);
        assertThat(InventoryKpis.find(kpis, InventoryKpis.Code.STOCK_LOSS).value()).isEqualByComparingTo("0.40");
        assertThat(InventoryKpis.find(kpis, InventoryKpis.Code.STOCK_LOSS).isMet()).isTrue();
        assertThat(InventoryKpis.find(kpis, InventoryKpis.Code.DAMAGED).value()).isEqualByComparingTo("1.20");
        assertThat(InventoryKpis.find(kpis, InventoryKpis.Code.DAMAGED).isMissed()).isTrue();
        // 3,000,000 x 365 / (10,000,000 x 30) = 3.65 times a year; management set no target
        InventoryKpis.Kpi turnover = InventoryKpis.find(kpis, InventoryKpis.Code.TURNOVER);
        assertThat(turnover.value()).isEqualByComparingTo("3.65");
        assertThat(turnover.state()).isEqualTo(InventoryKpis.State.NO_TARGET);
        // 49 of 50 copies back without a problem; 21 of 22 reconciliations on time; 3 of 4 variances in time
        assertThat(InventoryKpis.find(kpis, InventoryKpis.Code.DELIVERY_ACCURACY).value()).isEqualByComparingTo("98.0");
        assertThat(InventoryKpis.find(kpis, InventoryKpis.Code.DELIVERY_ACCURACY).isMet()).isTrue();
        assertThat(InventoryKpis.find(kpis, InventoryKpis.Code.RECONCILIATION).value()).isEqualByComparingTo("95.5");
        assertThat(InventoryKpis.find(kpis, InventoryKpis.Code.RECONCILIATION).isMissed()).isTrue();
        assertThat(InventoryKpis.find(kpis, InventoryKpis.Code.VARIANCE_RESOLUTION).value()).isEqualByComparingTo("75.0");
        // 25.5 days over 10 orders: 2.6 days, within the 3 agreed
        InventoryKpis.Kpi order = InventoryKpis.find(kpis, InventoryKpis.Code.ORDER_TO_DELIVERY);
        assertThat(order.value()).isEqualByComparingTo("2.6");
        assertThat(order.ceiling()).isTrue();
        assertThat(order.isMet()).isTrue();
    }

    @Test
    void ceilingsAreStrictForLossesAndInclusiveForDays() {
        InventoryKpis.Targets t = new InventoryKpis.Targets(bd("99"), bd("0.5"), bd("1"), bd("4"), bd("98"), bd("100"), bd("100"), bd("2.6"));
        // exactly 0.5 % lost misses "below 0.5 %"; exactly the agreed days meets "within"
        List<InventoryKpis.Kpi> kpis = InventoryKpis.of(inputs(198, 200, "50000", "0", "10000000", "10000000", "3000000", 30), t);
        assertThat(InventoryKpis.find(kpis, InventoryKpis.Code.STOCK_LOSS).isMissed()).isTrue();
        assertThat(InventoryKpis.find(kpis, InventoryKpis.Code.ORDER_TO_DELIVERY).isMet()).isTrue();
        assertThat(InventoryKpis.find(kpis, InventoryKpis.Code.TURNOVER).isMissed()).isTrue();
    }

    @Test
    void nothingToMeasureHasNoState() {
        InventoryKpis.Inputs none = new InventoryKpis.Inputs(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                30, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO);
        assertThat(InventoryKpis.of(none, POLICY)).allSatisfy(k -> {
            assertThat(k.value()).isNull();
            assertThat(k.state()).isEqualTo(InventoryKpis.State.NO_DATA);
        });
    }

    @Test
    void twoMonthsBelowCallForAReview() {
        BigDecimal target = bd("99");
        assertThat(InventoryKpis.monthsBelow(Arrays.asList(bd("99.5"), bd("98.0"), bd("97.5")), target)).isEqualTo(2);
        assertThat(InventoryKpis.monthsBelow(Arrays.asList(bd("98.0"), bd("99.0")), target)).isZero();
        // a month without a count ends the run: nothing was measured
        assertThat(InventoryKpis.monthsBelow(Arrays.asList(bd("98.0"), null, bd("97.0")), target)).isEqualTo(1);
        assertThat(InventoryKpis.monthsBelow(List.of(), target)).isZero();
    }

    @Test
    void reviewsAreOnTimeByTheirDueDay() {
        // Friday 16/10/2026 is signed off by Monday 19/10; September's reconciliation by its 5th working day of October (07/10)
        assertThat(InventoryKpis.dayReviewDue(LocalDate.of(2026, 10, 16))).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(InventoryKpis.monthReviewDue(YearMonth.of(2026, 9), 5)).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(InventoryKpis.onTime(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 19))).isTrue();
        assertThat(InventoryKpis.onTime(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 19))).isFalse();
        assertThat(InventoryKpis.onTime(null, LocalDate.of(2026, 10, 19))).isFalse();
    }

    @Test
    void reportPeriods() {
        LocalDate day = LocalDate.of(2026, 10, 16);
        InventoryPeriods.Period week = InventoryPeriods.of(InventoryPeriods.Kind.WEEK, day);
        assertThat(week.from()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(week.to()).isEqualTo(LocalDate.of(2026, 10, 18));
        InventoryPeriods.Period quarter = InventoryPeriods.of(InventoryPeriods.Kind.QUARTER, day);
        assertThat(quarter.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(quarter.to()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(InventoryPeriods.of(InventoryPeriods.Kind.YEAR, day).from()).isEqualTo(LocalDate.of(2026, 1, 1));
        InventoryPeriods.Period month = InventoryPeriods.of(InventoryPeriods.Kind.MONTH, day);
        assertThat(month.isRunning(day)).isTrue();
        assertThat(month.end(day)).isEqualTo(day);
        assertThat(month.days(day)).isEqualTo(16);
        assertThat(InventoryPeriods.previous(quarter).from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(InventoryPeriods.previous(week).to()).isEqualTo(LocalDate.of(2026, 10, 11));
    }
}
