package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FixedAssets.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The fixed-asset rules (C.08, U.12), pure and tested. Straight line over the useful life, which starts the month
 *               after the asset was acquired: each month takes what is left to depreciate (cost less residual value less the
 *               depreciation to date) over the months left of its life, rounded to the franc cent, so a month run late or an
 *               opening balance that was not exact spreads over the rest of the life, and the last month takes what is left.
 *               An asset is depreciated from a month (its first day) on; disposing of it takes its book value out against
 *               what it fetched: a loss is a debit, a gain a credit.
 * </pre>
 */
public final class FixedAssets {

    private FixedAssets() {
    }

    /** The month its useful life starts: the month after it was acquired. */
    public static YearMonth lifeStart(LocalDate acquiredOn) {
        return YearMonth.from(acquiredOn).plusMonths(1);
    }

    /** The first day of the month it is depreciated from by default: the month after it was acquired. */
    public static LocalDate defaultDepreciateFrom(LocalDate acquiredOn) {
        return lifeStart(acquiredOn).atDay(1);
    }

    /** The months of its life left in a month, that month included: at least 1, so what is left is taken once the life is over. */
    public static long monthsLeft(LocalDate acquiredOn, int lifeMonths, YearMonth month) {
        long used = ChronoUnit.MONTHS.between(lifeStart(acquiredOn), month);
        return Math.max(1, lifeMonths - Math.max(0, used));
    }

    /**
     * Its depreciation for a month: nothing before the month it is depreciated from or once nothing is left; otherwise what is
     * left over the months left, 2 decimals (half up), never more than what is left.
     */
    public static BigDecimal monthly(LocalDate acquiredOn, LocalDate depreciateFrom, int lifeMonths, BigDecimal depreciable,
                                     BigDecimal accumulated, YearMonth month) {
        if (month.isBefore(YearMonth.from(depreciateFrom))) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal left = depreciable.subtract(accumulated);
        if (left.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal share = left.divide(BigDecimal.valueOf(monthsLeft(acquiredOn, lifeMonths, month)), 2, RoundingMode.HALF_UP);
        return share.min(left).setScale(2, RoundingMode.HALF_UP);
    }

    /** Cost less the depreciation to date. */
    public static BigDecimal bookValue(BigDecimal cost, BigDecimal accumulated) {
        return cost.subtract(accumulated);
    }

    /** The loss on disposal (positive, a debit) or the gain (negative, a credit): book value less what it fetched. */
    public static BigDecimal disposalLoss(BigDecimal cost, BigDecimal accumulated, BigDecimal proceeds) {
        return bookValue(cost, accumulated).subtract(proceeds == null ? BigDecimal.ZERO : proceeds);
    }

    /** Whether a month's run may be posted on a day: once the month has ended, its last day included. */
    public static boolean mayRun(YearMonth month, LocalDate today) {
        return !month.atEndOfMonth().isAfter(today);
    }

    /** The month to run next: the month after the latest posted run, or, before the first run, the month before today's. */
    public static YearMonth nextRun(YearMonth latestPosted, LocalDate today) {
        return latestPosted != null ? latestPosted.plusMonths(1) : YearMonth.from(today).minusMonths(1);
    }
}
