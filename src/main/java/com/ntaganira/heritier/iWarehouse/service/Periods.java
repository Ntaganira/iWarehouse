package com.ntaganira.heritier.iWarehouse.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Periods.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Order of the monthly close (ACC-10). Months close one after the other from the ledger's first month,
 *               each once it has ended; so the closed months are always the first ones, and one day (the last day of
 *               the latest closed month) says what is closed. Pure, unit-tested.
 * </pre>
 */
public final class Periods {

    private Periods() {
    }

    /**
     * The month to close next: the one after the latest closed month, or the ledger's first month when none is closed, once it
     * has ended. None before the first journal.
     */
    public static Optional<YearMonth> nextToClose(LocalDate firstJournal, LocalDate closedThrough, LocalDate today) {
        if (firstJournal == null && closedThrough == null) {
            return Optional.empty();
        }
        YearMonth next = closedThrough == null ? YearMonth.from(firstJournal) : YearMonth.from(closedThrough).plusMonths(1);
        return next.atEndOfMonth().isBefore(today) ? Optional.of(next) : Optional.empty();
    }

    /** A day on or before the last day of the latest closed month is closed. */
    public static boolean isClosed(LocalDate day, LocalDate closedThrough) {
        return closedThrough != null && !day.isAfter(closedThrough);
    }
}
