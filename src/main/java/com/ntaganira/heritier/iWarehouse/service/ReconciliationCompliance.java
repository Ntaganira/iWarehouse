package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ReconciliationCompliance.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Whether an account was reconciled on time each month (BP Table 9, Annex D, IMP 19): a month is reconciled
 *               by the first reconciliation in force whose statement reaches its last day; on time when that was done
 *               by the Settings' working day of the next month. Compliance = months on time out of the months due.
 *               Pure and tested.
 * </pre>
 */
public final class ReconciliationCompliance {

    private ReconciliationCompliance() {
    }

    public enum State {
        ON_TIME, LATE, MISSING, PENDING
    }

    /** A reconciliation in force: the statement's date and the day it was made. */
    public record Done(LocalDate statementDate, LocalDate reconciledOn) {
    }

    public record Month(YearMonth month, LocalDate due, State state, LocalDate reconciledOn) {
    }

    public record Result(List<Month> months, int onTime, int due) {

        /** Months on time out of the months due, in per cent (one decimal); null before any month is due. */
        public BigDecimal getPercent() {
            return due == 0 ? null : BigDecimal.valueOf(onTime * 100L).divide(BigDecimal.valueOf(due), 1, RoundingMode.HALF_UP);
        }

        public long getMissing() {
            return months.stream().filter(m -> m.state() == State.MISSING).count();
        }
    }

    /** The day a month is to be reconciled by: the n-th working day of the next month. */
    public static LocalDate due(YearMonth month, int workingDay) {
        return WorkingDays.nth(month.plusMonths(1), workingDay);
    }

    /** The day a month was reconciled: the earliest reconciliation whose statement reaches its last day. */
    public static Optional<LocalDate> reconciledOn(List<Done> done, YearMonth month) {
        LocalDate end = month.atEndOfMonth();
        return done.stream().filter(d -> !d.statementDate().isBefore(end)).map(Done::reconciledOn).min(LocalDate::compareTo);
    }

    /** Each month from the first to the last, newest first. */
    public static Result of(List<Done> done, YearMonth from, YearMonth to, int workingDay, LocalDate today) {
        List<Month> months = new ArrayList<>();
        int onTime = 0;
        int due = 0;
        for (YearMonth m = to; !m.isBefore(from); m = m.minusMonths(1)) {
            LocalDate dueDay = due(m, workingDay);
            LocalDate on = reconciledOn(done, m).orElse(null);
            State state = on != null ? (on.isAfter(dueDay) ? State.LATE : State.ON_TIME) : today.isAfter(dueDay) ? State.MISSING : State.PENDING;
            if (state != State.PENDING) {
                due++;
            }
            if (state == State.ON_TIME) {
                onTime++;
            }
            months.add(new Month(m, dueDay, state, on));
        }
        return new Result(months, onTime, due);
    }
}
