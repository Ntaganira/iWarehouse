package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FxRevaluations.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Month-end revaluation of open foreign balances (ACC-08, unrealised FX). What is owed in a currency
 *               is worth that amount at the month's last rate, rounded to the cent: what it was booked at less that
 *               is the gain (positive) or loss. A month can be revalued once it has ended, from the ledger's first
 *               month on. Pure, unit-tested.
 * </pre>
 */
public final class FxRevaluations {

    private FxRevaluations() {
    }

    /** What is owed revalued: the RWF at the rate, and booked less that (positive a gain, negative a loss). */
    public record Revalued(BigDecimal revalued, BigDecimal gainLoss) {
    }

    /** {@code owed} in the currency and {@code booked} in RWF (both credits less debits), at {@code rate}. */
    public static Revalued revalue(BigDecimal owed, BigDecimal booked, BigDecimal rate) {
        BigDecimal revalued = owed.multiply(rate).setScale(Journal.SCALE, RoundingMode.HALF_UP);
        return new Revalued(revalued, booked.setScale(Journal.SCALE, RoundingMode.HALF_UP).subtract(revalued));
    }

    /**
     * The months that can be revalued, newest first: from the month of the ledger's first journal to the last month ended
     * before today, less the months revalued already (given by their last days). None before the first journal.
     */
    public static List<YearMonth> months(LocalDate firstJournal, LocalDate today, Collection<LocalDate> revalued) {
        if (firstJournal == null) {
            return List.of();
        }
        Set<YearMonth> done = revalued.stream().map(YearMonth::from).collect(Collectors.toSet());
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m = YearMonth.from(today).minusMonths(1); !m.isBefore(YearMonth.from(firstJournal)); m = m.minusMonths(1)) {
            if (!done.contains(m)) {
                months.add(m);
            }
        }
        return months;
    }

    /** A month has ended once today is past its last day. */
    public static boolean hasEnded(YearMonth month, LocalDate today) {
        return month.atEndOfMonth().isBefore(today);
    }
}
