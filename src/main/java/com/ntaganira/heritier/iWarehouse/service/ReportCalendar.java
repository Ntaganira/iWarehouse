package com.ntaganira.heritier.iWarehouse.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Optional;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ReportCalendar.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The reporting calendar (BP 5.3): the daily cash report by the Settings' time the next day, the weekly
 *               receivables and stock on Mondays, the month's accounts by the Settings' working day, the quarterly Board
 *               pack once a quarter has ended. Says what is due at a moment; the service tells people once. Pure and
 *               tested.
 * </pre>
 */
public final class ReportCalendar {

    private ReportCalendar() {
    }

    /** The weekly reports go out on Mondays from this hour. */
    static final LocalTime WEEKLY_FROM = LocalTime.of(8, 0);

    /** The latest day whose close should be prepared by now: yesterday once today's deadline has passed, else the day before. */
    public static LocalDate lastDueClose(LocalDateTime now, LocalTime by) {
        return now.toLocalTime().isBefore(by) ? now.toLocalDate().minusDays(2) : now.toLocalDate().minusDays(1);
    }

    /** "08:30" read as a time; the default when it is not one. */
    public static LocalTime time(String value, LocalTime fallback) {
        try {
            return LocalTime.parse(value == null ? "" : value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** On Mondays from 08:00, the week's reminder (its key names the day). */
    public static Optional<String> weekly(LocalDateTime now) {
        if (now.getDayOfWeek() == DayOfWeek.MONDAY && !now.toLocalTime().isBefore(WEEKLY_FROM)) {
            return Optional.of("WEEKLY:" + now.toLocalDate());
        }
        return Optional.empty();
    }

    /** The month whose accounts are late: the last one, once the Settings' working day of this month has passed. */
    public static Optional<YearMonth> accountsLate(LocalDate today, int workingDay) {
        YearMonth month = YearMonth.from(today);
        return today.isAfter(WorkingDays.nth(month, workingDay)) ? Optional.of(month.minusMonths(1)) : Optional.empty();
    }

    /** In the first month of a quarter, from its first working day: the quarter that ended ("2026-Q3"). */
    public static Optional<String> quarterEnded(LocalDate today) {
        int month = today.getMonthValue();
        if ((month - 1) % 3 != 0 || today.isBefore(WorkingDays.nth(YearMonth.from(today), 1))) {
            return Optional.empty();
        }
        LocalDate lastQuarterDay = today.withDayOfMonth(1).minusDays(1);
        return Optional.of(lastQuarterDay.getYear() + "-Q" + ((lastQuarterDay.getMonthValue() - 1) / 3 + 1));
    }
}
