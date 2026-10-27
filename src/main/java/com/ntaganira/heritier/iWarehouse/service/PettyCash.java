package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PettyCash.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The petty cash rules of the Cost Management &amp; Expenditure Control Policy (CMP 07), pure and tested: a fund
 *               below the Settings' share of its float is topped up, and a top-up brings it back to its float; a payment is
 *               at most the Settings' amount and what the fund holds; the custodian counts it every working day; Finance
 *               counts it by surprise each month, due from the Settings' day.
 * </pre>
 */
public final class PettyCash {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PettyCash() {
    }

    /** Below the share of its float that calls for a top-up (CMP 07: 25 %). */
    public static boolean belowTrigger(BigDecimal balance, BigDecimal imprest, BigDecimal percent) {
        return balance.compareTo(trigger(imprest, percent)) < 0;
    }

    /** What the fund holds when it is to be topped up: its float times the Settings' share. */
    public static BigDecimal trigger(BigDecimal imprest, BigDecimal percent) {
        return imprest.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** What brings the fund back to its float (never negative). */
    public static BigDecimal topUpToFloat(BigDecimal balance, BigDecimal imprest) {
        return imprest.subtract(balance).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /** Why a payment of this amount is refused, or null: above the largest payment, or more than the fund holds. */
    public static String paymentRefusal(BigDecimal amount, BigDecimal maxPayment, BigDecimal balance) {
        if (maxPayment != null && maxPayment.signum() > 0 && amount.compareTo(maxPayment) > 0) {
            return "TOO_MUCH";
        }
        if (amount.compareTo(balance) > 0) {
            return "NOT_ENOUGH";
        }
        return null;
    }

    /** The working days from one day to another (both included) on which the custodian made no daily count. */
    public static List<LocalDate> daysNotCounted(Collection<LocalDate> counted, LocalDate from, LocalDate to) {
        Set<LocalDate> done = new HashSet<>(counted);
        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (WorkingDays.isWorking(d) && !done.contains(d)) {
                missing.add(d);
            }
        }
        return missing;
    }

    /** A surprise count is due: none this month yet and the Settings' day of the month has come (CMP 07). */
    public static boolean auditDue(boolean auditedThisMonth, LocalDate today, int auditDay) {
        return !auditedThisMonth && today.getDayOfMonth() >= Math.max(1, auditDay);
    }
}
