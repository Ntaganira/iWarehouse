package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : Reconciliations.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Reconciling a statement with the ledger (ACC-12). The lines ticked (debits less credits) must take the
 *               previous statement's balance to the new one; the lines not ticked are outstanding: deposits (debits)
 *               and payments (credits) the bank has not shown yet. So the account's balance less the outstanding
 *               deposits plus the outstanding payments is the statement's balance. Pure, unit-tested.
 * </pre>
 */
public final class Reconciliations {

    private Reconciliations() {
    }

    /** A ledger line that may be cleared: its id, debit and credit. */
    public record Line(UUID id, BigDecimal debit, BigDecimal credit) {
    }

    /** What the lines ticked clear, what is still to explain (0 when reconciled) and what is outstanding. */
    public record Result(BigDecimal cleared, BigDecimal difference, BigDecimal outstandingDeposits, BigDecimal outstandingPayments) {

        public boolean isReconciled() {
            return difference.signum() == 0;
        }
    }

    public static Result of(BigDecimal previousBalance, BigDecimal statementBalance, List<Line> open, Collection<UUID> ticked) {
        BigDecimal cleared = BigDecimal.ZERO;
        BigDecimal deposits = BigDecimal.ZERO;
        BigDecimal payments = BigDecimal.ZERO;
        for (Line l : open) {
            if (ticked.contains(l.id())) {
                cleared = cleared.add(l.debit()).subtract(l.credit());
            } else {
                deposits = deposits.add(l.debit());
                payments = payments.add(l.credit());
            }
        }
        return new Result(cleared, statementBalance.subtract(previousBalance).subtract(cleared), deposits, payments);
    }
}
