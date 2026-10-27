package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Budgets.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The budget rules of the policy (CMP 04, 10; Annex D), pure and tested: an annual amount spread over the
 *               months (whole RWF, adding up exactly), year to date, a variance and its share of the budget (flagged
 *               beyond the Settings' tolerance), the share a revision changes a line (the Delegation of Authority decides
 *               by it), the Settings' "MM-dd" days, a period's target from monthly targets by the share of their days, the
 *               gross margin and an annual amount pro rata.
 * </pre>
 */
public final class Budgets {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private Budgets() {
    }

    /** An annual amount over twelve months in whole RWF; December takes the rest, so the months add up to it exactly. */
    public static BigDecimal[] spread(BigDecimal annual) {
        BigDecimal whole = annual.setScale(0, RoundingMode.HALF_UP);
        BigDecimal month = whole.divide(BigDecimal.valueOf(12), 0, RoundingMode.DOWN);
        BigDecimal[] months = new BigDecimal[12];
        Arrays.fill(months, month.setScale(2));
        months[11] = whole.subtract(month.multiply(BigDecimal.valueOf(11))).setScale(2);
        return months;
    }

    public static BigDecimal sum(BigDecimal[] months) {
        return Arrays.stream(months).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** January up to the month (1 to 12) included. */
    public static BigDecimal toDate(BigDecimal[] months, int month) {
        BigDecimal total = BigDecimal.ZERO;
        for (int m = 0; m < Math.min(12, month); m++) {
            total = total.add(months[m]);
        }
        return total;
    }

    /** A budget against what happened: the difference and its share of the budget. */
    public record Variance(BigDecimal budget, BigDecimal actual) {

        /** Actual less budget. */
        public BigDecimal getAmount() {
            return actual.subtract(budget);
        }

        /** The difference as a share of the budget (1 decimal); null when nothing was budgeted. */
        public BigDecimal getPercent() {
            return budget.signum() == 0 ? null : getAmount().multiply(HUNDRED).divide(budget, 1, RoundingMode.HALF_UP);
        }

        /** Beyond the tolerance either way, or spent or earned with nothing budgeted. */
        public boolean flagged(BigDecimal tolerance) {
            BigDecimal percent = getPercent();
            if (percent == null) {
                return actual.signum() != 0;
            }
            return percent.abs().compareTo(tolerance) > 0;
        }
    }

    /** The share a revision changes a line: |new - old| / old x 100 (2 decimals); 100 when the line had nothing. */
    public static BigDecimal revisionPercent(BigDecimal oldTotal, BigDecimal newTotal) {
        if (oldTotal.signum() == 0) {
            return newTotal.signum() == 0 ? BigDecimal.ZERO.setScale(2) : HUNDRED.setScale(2);
        }
        return newTotal.subtract(oldTotal).abs().multiply(HUNDRED).divide(oldTotal, 2, RoundingMode.HALF_UP);
    }

    /** Whether the Settings' "MM-dd" is a day of the year (29 February is not). */
    public static boolean isMonthDay(String value) {
        try {
            MonthDay day = MonthDay.parse(value, MONTH_DAY);
            return !(day.getMonthValue() == 2 && day.getDayOfMonth() == 29);
        } catch (DateTimeParseException | NullPointerException e) {
            return false;
        }
    }

    /** The day of a year a Settings' "MM-dd" names; a day past the month's end is its last day (02-30: 28 or 29 February). */
    public static LocalDate dayOf(String monthDay, int year) {
        String[] parts = monthDay.trim().split("-");
        YearMonth month = YearMonth.of(year, Integer.parseInt(parts[0]));
        return month.atDay(Math.max(1, Math.min(month.lengthOfMonth(), Integer.parseInt(parts[1]))));
    }

    /** A period's target: each month's target times the share of its days the period covers (2 decimals). */
    public static BigDecimal targetFor(Map<YearMonth, BigDecimal> monthly, LocalDate from, LocalDate to) {
        BigDecimal total = BigDecimal.ZERO;
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            BigDecimal target = monthly.get(m);
            if (target == null || target.signum() == 0) {
                continue;
            }
            LocalDate start = from.isAfter(m.atDay(1)) ? from : m.atDay(1);
            LocalDate end = to.isBefore(m.atEndOfMonth()) ? to : m.atEndOfMonth();
            long days = end.toEpochDay() - start.toEpochDay() + 1;
            total = total.add(target.multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(m.lengthOfMonth()), 10, RoundingMode.HALF_UP));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** The gross margin: (revenue - cost of sales) / revenue x 100 (1 decimal); null without revenue. */
    public static BigDecimal marginPercent(BigDecimal revenue, BigDecimal costOfSales) {
        return revenue.signum() <= 0 ? null : revenue.subtract(costOfSales).multiply(HUNDRED).divide(revenue, 1, RoundingMode.HALF_UP);
    }

    /** An annual amount for the months gone (of twelve), in whole RWF. */
    public static BigDecimal proRata(BigDecimal annual, int months) {
        return annual.multiply(BigDecimal.valueOf(Math.max(0, Math.min(12, months)))).divide(BigDecimal.valueOf(12), 0, RoundingMode.HALF_UP);
    }

    /** The first day of each month of a year: the pages name the months from them in the reader's language. */
    public static List<LocalDate> monthStarts(int year) {
        List<LocalDate> days = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            days.add(LocalDate.of(year, m, 1));
        }
        return days;
    }

    /** Actual against target as a share (1 decimal); null without a target. */
    public static BigDecimal achieved(BigDecimal actual, BigDecimal target) {
        return target.signum() <= 0 ? null : actual.multiply(HUNDRED).divide(target, 1, RoundingMode.HALF_UP);
    }
}
