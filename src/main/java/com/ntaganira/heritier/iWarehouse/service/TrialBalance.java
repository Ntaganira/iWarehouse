package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Account;

import java.math.BigDecimal;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TrialBalance.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Trial balance (ACC-11, AT-10): each account with lines, its balance on the debit or the
 *               credit side, and the two totals, equal when the books balance. Pure, unit-tested.
 * </pre>
 */
public final class TrialBalance {

    private TrialBalance() {
    }

    /** An account, the debits and credits posted to it, and its balance on one side. */
    public record Row(Account account, BigDecimal debits, BigDecimal credits) {

        public BigDecimal getNet() {
            return debits.subtract(credits);
        }

        /** The balance when it is on the debit side, otherwise 0. */
        public BigDecimal getDebit() {
            return getNet().signum() > 0 ? getNet() : BigDecimal.ZERO;
        }

        /** The balance when it is on the credit side, otherwise 0. */
        public BigDecimal getCredit() {
            return getNet().signum() < 0 ? getNet().negate() : BigDecimal.ZERO;
        }

        /** The balance on the account's normal side (debit for assets and expenses); negative when on the other. */
        public BigDecimal getBalance() {
            return account.getType().isDebitNormal() ? getNet() : getNet().negate();
        }
    }

    /** The rows by account code and the two column totals. */
    public record Result(List<Row> rows, BigDecimal debit, BigDecimal credit) {

        public boolean isBalanced() {
            return debit.compareTo(credit) == 0;
        }

        public BigDecimal getDifference() {
            return debit.subtract(credit);
        }
    }

    /** From rows of (account id, debits, credits); accounts without lines are left out. */
    public static Result of(Collection<Account> accounts, Collection<Object[]> sums) {
        Map<UUID, Account> byId = new HashMap<>();
        accounts.forEach(a -> byId.put(a.getId(), a));
        List<Row> rows = new ArrayList<>();
        for (Object[] s : sums) {
            Account account = byId.get((UUID) s[0]);
            if (account != null) {
                rows.add(new Row(account, (BigDecimal) s[1], (BigDecimal) s[2]));
            }
        }
        rows.sort(Comparator.comparing(r -> r.account().getCode()));
        BigDecimal debit = rows.stream().map(Row::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(Row::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Result(rows, debit, credit);
    }
}
