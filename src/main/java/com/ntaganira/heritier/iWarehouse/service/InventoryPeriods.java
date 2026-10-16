package com.ntaganira.heritier.iWarehouse.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : InventoryPeriods.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The periods of the inventory reports (Inventory Policy 18): the week (Monday to Sunday), the month, the
 *               quarter and the year a day falls in, the one before it, and how far it has run by a day. Pure and tested.
 * </pre>
 */
public final class InventoryPeriods {

    private InventoryPeriods() {
    }

    public enum Kind { WEEK, MONTH, QUARTER, YEAR }

    /** A period, both days included. */
    public record Period(Kind kind, LocalDate from, LocalDate to) {

        /** The last day reached by {@code today}: the period's end, or today while it runs. */
        public LocalDate end(LocalDate today) {
            return to.isAfter(today) ? today : to;
        }

        public boolean isRunning(LocalDate today) {
            return !today.isBefore(from) && !today.isAfter(to);
        }

        /** Days covered up to {@code today} (at least 1). */
        public int days(LocalDate today) {
            return (int) Math.max(1, ChronoUnit.DAYS.between(from, end(today)) + 1);
        }

        public YearMonth firstMonth() {
            return YearMonth.from(from);
        }

        public YearMonth lastMonth() {
            return YearMonth.from(to);
        }
    }

    /** The period of a kind that a day falls in. */
    public static Period of(Kind kind, LocalDate day) {
        return switch (kind) {
            case WEEK -> {
                LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new Period(kind, monday, monday.plusDays(6));
            }
            case MONTH -> new Period(kind, day.withDayOfMonth(1), day.with(TemporalAdjusters.lastDayOfMonth()));
            case QUARTER -> {
                int first = (day.getMonthValue() - 1) / 3 * 3 + 1;
                LocalDate start = LocalDate.of(day.getYear(), first, 1);
                yield new Period(kind, start, start.plusMonths(3).minusDays(1));
            }
            case YEAR -> new Period(kind, LocalDate.of(day.getYear(), 1, 1), LocalDate.of(day.getYear(), 12, 31));
        };
    }

    /** The period just before this one, of the same kind (the trend). */
    public static Period previous(Period period) {
        return of(period.kind(), period.from().minusDays(1));
    }
}
