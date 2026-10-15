package com.ntaganira.heritier.iWarehouse.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : WorkingDays.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Working days (Monday to Friday) for the deadlines of the client's calendar: reconciliations by the fifth
 *               working day, the month's accounts by the tenth (BP 5.3, Table 9). Pure and tested.
 * </pre>
 */
public final class WorkingDays {

    private WorkingDays() {
    }

    public static boolean isWorking(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    /** The n-th working day of a month (n from 1; past the month's working days, its last day of work and beyond). */
    public static LocalDate nth(YearMonth month, int n) {
        LocalDate day = month.atDay(1);
        int count = isWorking(day) ? 1 : 0;
        while (count < Math.max(1, n)) {
            day = day.plusDays(1);
            if (isWorking(day)) {
                count++;
            }
        }
        return day;
    }

    /** The first working day on or after a day. */
    public static LocalDate onOrAfter(LocalDate day) {
        LocalDate d = day;
        while (!isWorking(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** The working day before a day. */
    public static LocalDate previous(LocalDate day) {
        LocalDate d = day.minusDays(1);
        while (!isWorking(d)) {
            d = d.minusDays(1);
        }
        return d;
    }
}
