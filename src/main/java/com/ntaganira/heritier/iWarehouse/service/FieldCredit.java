package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FieldCredit.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Credit on the road (BP 7.3 rule 8, F-10, F-11; decision D8): only to an approved customer (one whose
 *               type allows credit, with a limit), within the limit less what they owe, never while an invoice of theirs
 *               is overdue past the grace (when the Settings block it). What the phone may grant is frozen at the trip's
 *               download; the sale is checked again at sync with today's figures. Pure and tested.
 * </pre>
 */
public final class FieldCredit {

    private FieldCredit() {
    }

    /** What a customer may still take on credit: none (null) without credit, never below 0, 0 while blocked by an overdue invoice. */
    public static BigDecimal left(boolean creditAllowed, BigDecimal limit, BigDecimal owed, boolean blockedByOverdue) {
        if (!creditAllowed || limit == null || limit.signum() <= 0) {
            return null;
        }
        if (blockedByOverdue) {
            return BigDecimal.ZERO;
        }
        BigDecimal left = limit.subtract(owed == null ? BigDecimal.ZERO : owed);
        return left.signum() < 0 ? BigDecimal.ZERO : left;
    }

    /** Why credit of this amount is refused, or empty: field credit off, a customer without credit, beyond what is left. */
    public static Optional<String> problem(boolean fieldCredit, BigDecimal credit, BigDecimal left) {
        if (credit == null || credit.signum() <= 0) {
            return Optional.empty();
        }
        if (!fieldCredit) {
            return Optional.of("credit is not given on the road");
        }
        if (left == null) {
            return Optional.of("the customer has no credit");
        }
        if (credit.compareTo(left) > 0) {
            return Optional.of("credit of " + credit.toPlainString() + " beyond the " + left.toPlainString() + " left");
        }
        return Optional.empty();
    }
}
