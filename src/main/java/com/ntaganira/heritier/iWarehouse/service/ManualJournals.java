package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.AccountKey;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ManualJournals.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Rules of manual journals (ACC-05). A control account's balance is carried by documents or a
 *               subledger (the stock valuation, customers, suppliers, receipts not invoiced, tills, driver floats,
 *               claims): only those documents post to it, never a manual journal, or the two would drift apart.
 *               A line is a debit or a credit, never both; the lines balance. Pure, unit-tested.
 * </pre>
 */
public final class ManualJournals {

    /** Accounts posted by their documents only. */
    public static final Set<AccountKey> CONTROLLED = Collections.unmodifiableSet(EnumSet.of(AccountKey.INVENTORY, AccountKey.RECEIVABLE,
            AccountKey.PAYABLE, AccountKey.GRNI, AccountKey.CASH, AccountKey.DRIVER_FLOAT, AccountKey.CLAIMS));

    private ManualJournals() {
    }

    public static boolean isControlled(AccountKey key) {
        return key != null && CONTROLLED.contains(key);
    }

    /** A line's amounts; null counts as zero. */
    public record Amounts(BigDecimal debit, BigDecimal credit) {

        public BigDecimal getDebit() {
            return debit == null ? BigDecimal.ZERO : debit;
        }

        public BigDecimal getCredit() {
            return credit == null ? BigDecimal.ZERO : credit;
        }

        /** A debit or a credit above zero, not both. */
        public boolean isOneSided() {
            return getDebit().signum() > 0 != getCredit().signum() > 0;
        }
    }

    /** The debits and credits of the lines. */
    public record Totals(BigDecimal debits, BigDecimal credits) {

        public BigDecimal getDifference() {
            return debits.subtract(credits);
        }

        public boolean isBalanced() {
            return debits.compareTo(credits) == 0;
        }
    }

    public static Totals totals(List<Amounts> lines) {
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (Amounts a : lines) {
            debits = debits.add(a.getDebit());
            credits = credits.add(a.getCredit());
        }
        return new Totals(debits, credits);
    }
}
