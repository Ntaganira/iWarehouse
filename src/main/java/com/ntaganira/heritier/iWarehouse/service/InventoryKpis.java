package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : InventoryKpis.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The inventory KPIs of a period against their targets (Inventory Policy 19, Annex D):
 *               - inventory accuracy: units found where expected as a % of those expected, at or above target;
 *               - stock loss and damaged stock: their value as a % of the average inventory value, below target;
 *               - turnover: the cost of sales over the average inventory value, as times a year (target set by
 *                 management, or none);
 *               - delivery accuracy: signed copies back without a shortage or damage as a % of those back;
 *               - reconciliation compliance and variance resolution: done on time as a % of those due;
 *               - order-to-delivery: the average days from payment to the last hand-over, at most the target.
 *               A KPI without anything to measure has no state. Accuracy below target for consecutive months calls for a
 *               formal review (monthsBelow). Pure and tested.
 * </pre>
 */
public final class InventoryKpis {

    private InventoryKpis() {
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    public enum Code { ACCURACY, STOCK_LOSS, DAMAGED, TURNOVER, DELIVERY_ACCURACY, RECONCILIATION, VARIANCE_RESOLUTION, ORDER_TO_DELIVERY }

    public enum State { MET, MISSED, NO_TARGET, NO_DATA }

    /** What a period's KPIs are measured from. Values in RWF; inventory values from the Inventory account. */
    public record Inputs(int matched, int expected, BigDecimal lostValue, BigDecimal damagedValue, BigDecimal openingValue,
                         BigDecimal closingValue, BigDecimal costOfSales, int days, long copiesBack, long copiesWithIssue,
                         int reconciliationsDue, int reconciliationsOnTime, int variancesDue, int variancesOnTime,
                         int orders, BigDecimal orderDaysTotal) {

        /** The average inventory value: the opening and closing values' mean. */
        public BigDecimal averageValue() {
            return openingValue.add(closingValue).divide(TWO, 2, RoundingMode.HALF_UP);
        }
    }

    /** The targets (Settings); turnover may have none. */
    public record Targets(BigDecimal accuracy, BigDecimal lossMax, BigDecimal damageMax, BigDecimal turnover, BigDecimal deliveryAccuracy,
                          BigDecimal reconciliation, BigDecimal varianceResolution, BigDecimal orderDays) {
    }

    /** One KPI: its value (null without data), its target (null without one), whether the target is a ceiling, and its state. */
    public record Kpi(Code code, BigDecimal value, BigDecimal target, boolean ceiling, State state) {

        public boolean isMet() {
            return state == State.MET;
        }

        public boolean isMissed() {
            return state == State.MISSED;
        }
    }

    public static List<Kpi> of(Inputs in, Targets t) {
        BigDecimal average = in.averageValue();
        List<Kpi> kpis = new ArrayList<>();
        kpis.add(floor(Code.ACCURACY, percent(in.matched(), in.expected()), t.accuracy()));
        kpis.add(below(Code.STOCK_LOSS, average.signum() > 0 ? share(in.lostValue(), average) : null, t.lossMax()));
        kpis.add(below(Code.DAMAGED, average.signum() > 0 ? share(in.damagedValue(), average) : null, t.damageMax()));
        kpis.add(floor(Code.TURNOVER, turnover(in.costOfSales(), average, in.days()), t.turnover()));
        kpis.add(floor(Code.DELIVERY_ACCURACY, percent(in.copiesBack() - in.copiesWithIssue(), in.copiesBack()), t.deliveryAccuracy()));
        kpis.add(floor(Code.RECONCILIATION, percent(in.reconciliationsOnTime(), in.reconciliationsDue()), t.reconciliation()));
        kpis.add(floor(Code.VARIANCE_RESOLUTION, percent(in.variancesOnTime(), in.variancesDue()), t.varianceResolution()));
        BigDecimal days = in.orders() <= 0 ? null : in.orderDaysTotal().divide(BigDecimal.valueOf(in.orders()), 1, RoundingMode.HALF_UP);
        kpis.add(atMost(Code.ORDER_TO_DELIVERY, days, t.orderDays()));
        return kpis;
    }

    public static Kpi find(List<Kpi> kpis, Code code) {
        return kpis.stream().filter(k -> k.code() == code).findFirst().orElse(null);
    }

    /** A part of a whole in per cent, one decimal; null without a whole. */
    static BigDecimal percent(long part, long whole) {
        if (whole <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    /** A value as a % of another, two decimals (losses are small). */
    static BigDecimal share(BigDecimal value, BigDecimal of) {
        return value.multiply(HUNDRED).divide(of, 2, RoundingMode.HALF_UP);
    }

    /** Times a year: the period's cost of sales over the average value, scaled to 365 days; null without stock or days. */
    static BigDecimal turnover(BigDecimal costOfSales, BigDecimal average, int days) {
        if (average.signum() <= 0 || days <= 0) {
            return null;
        }
        return costOfSales.multiply(BigDecimal.valueOf(365)).divide(average.multiply(BigDecimal.valueOf(days)), 2, RoundingMode.HALF_UP);
    }

    /**
     * How many months in a row, ending with the latest, accuracy stayed below target (IMP 19). Months oldest first; a
     * month without a count (null) ends the run: nothing was measured.
     */
    public static int monthsBelow(List<BigDecimal> accuracies, BigDecimal target) {
        int run = 0;
        for (int i = accuracies.size() - 1; i >= 0; i--) {
            BigDecimal a = accuracies.get(i);
            if (a == null || a.compareTo(target) >= 0) {
                break;
            }
            run++;
        }
        return run;
    }

    /** A day's inventory report is signed off on time by the end of the next working day (IMP 14.1). */
    public static LocalDate dayReviewDue(LocalDate day) {
        return WorkingDays.onOrAfter(day.plusDays(1));
    }

    /** A month's stock reconciliation is signed off on time by the Settings' working day of the next month (BP Table 9). */
    public static LocalDate monthReviewDue(YearMonth month, int workingDay) {
        return WorkingDays.nth(month.plusMonths(1), workingDay);
    }

    /** Whether something due on a day was done on time (done that day or before). */
    public static boolean onTime(LocalDate done, LocalDate due) {
        return done != null && !done.isAfter(due);
    }

    private static Kpi floor(Code code, BigDecimal value, BigDecimal target) {
        if (value == null) {
            return new Kpi(code, null, target, false, State.NO_DATA);
        }
        if (target == null) {
            return new Kpi(code, value, null, false, State.NO_TARGET);
        }
        return new Kpi(code, value, target, false, value.compareTo(target) >= 0 ? State.MET : State.MISSED);
    }

    /** A ceiling the value must stay under (loss and damage: "below 0.5 %"). */
    private static Kpi below(Code code, BigDecimal value, BigDecimal target) {
        if (value == null) {
            return new Kpi(code, null, target, true, State.NO_DATA);
        }
        if (target == null) {
            return new Kpi(code, value, null, true, State.NO_TARGET);
        }
        return new Kpi(code, value, target, true, value.compareTo(target) < 0 ? State.MET : State.MISSED);
    }

    /** A ceiling the value may reach (order-to-delivery: within the agreed days). */
    private static Kpi atMost(Code code, BigDecimal value, BigDecimal target) {
        if (value == null) {
            return new Kpi(code, null, target, true, State.NO_DATA);
        }
        if (target == null) {
            return new Kpi(code, value, null, true, State.NO_TARGET);
        }
        return new Kpi(code, value, target, true, value.compareTo(target) <= 0 ? State.MET : State.MISSED);
    }
}
