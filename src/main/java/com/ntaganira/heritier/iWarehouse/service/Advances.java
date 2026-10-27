package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Advances.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The staff advance rules of the policy (CMP 08, 10), pure and tested: a journey's per diem (days of meals and
 *               incidentals, nights of accommodation), the day it is retired by (the Settings' working days after it is paid
 *               or the journey ends), the rest when it is retired (returned or paid back), what a pay line recovers (never
 *               more than the net pay before it), and the indicators: the share of advances due that were retired, and
 *               retired on time.
 * </pre>
 */
public final class Advances {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Advances() {
    }

    /** Days of a journey, departure and return included. */
    public static int days(LocalDate departOn, LocalDate returnOn) {
        return (int) ChronoUnit.DAYS.between(departOn, returnOn) + 1;
    }

    /** Nights away: one fewer than the days. */
    public static int nights(LocalDate departOn, LocalDate returnOn) {
        return (int) ChronoUnit.DAYS.between(departOn, returnOn);
    }

    /** The per diem of a journey in the rate's currency: days x (meals + incidentals) + nights x accommodation. */
    public static BigDecimal perDiem(BigDecimal daily, BigDecimal nightly, int days, int nights) {
        return daily.multiply(BigDecimal.valueOf(days)).add(nightly.multiply(BigDecimal.valueOf(nights))).setScale(2, RoundingMode.HALF_UP);
    }

    /** RWF of a foreign per diem at a rate, rounded once to whole RWF (the advance is paid in RWF). */
    public static BigDecimal inRwf(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** The day it is retired by: the Settings' working days after it was paid, or after the journey ended when later. */
    public static LocalDate dueOn(LocalDate paidOn, LocalDate returnOn, int workingDays) {
        LocalDate from = returnOn != null && returnOn.isAfter(paidOn) ? returnOn : paidOn;
        return WorkingDays.after(from, workingDays);
    }

    /** The rest when it is retired: what is still owed less what the receipts show (positive: returned; negative: paid back). */
    public static BigDecimal settlement(BigDecimal outstanding, BigDecimal spent) {
        return outstanding.subtract(spent).setScale(2, RoundingMode.HALF_UP);
    }

    /** What a pay line takes back: what is owed, at most the net pay before it, never negative. */
    public static BigDecimal deduction(BigDecimal outstanding, BigDecimal netBefore) {
        if (outstanding.signum() <= 0 || netBefore.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return outstanding.min(netBefore).setScale(2, RoundingMode.HALF_UP);
    }

    /** One advance due in a period: whether it was retired (with receipts) and on what day. */
    public record Due(LocalDate dueOn, LocalDate retiredOn) {
    }

    /** The advances due in a period, how many were retired, and how many by their due day (CMP 10). */
    public record Rates(int due, int retired, int onTime) {

        /** Advance retirement (CMP 10: 100 %), null when none was due. */
        public BigDecimal retirementPercent() {
            return percent(retired);
        }

        /** Expense claim timeliness (CMP 10: at least 95 %), null when none was due. */
        public BigDecimal timelinessPercent() {
            return percent(onTime);
        }

        private BigDecimal percent(int part) {
            return due == 0 ? null : BigDecimal.valueOf(part).multiply(HUNDRED).divide(BigDecimal.valueOf(due), 1, RoundingMode.HALF_UP);
        }
    }

    public static Rates rates(Collection<Due> due) {
        int retired = 0;
        int onTime = 0;
        for (Due d : due) {
            if (d.retiredOn() != null) {
                retired++;
                if (!d.retiredOn().isAfter(d.dueOn())) {
                    onTime++;
                }
            }
        }
        return new Rates(due.size(), retired, onTime);
    }
}
