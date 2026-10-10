package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.AccountType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FinancialStatements.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : The financial statements (ACC-11) from the accounts' debits and credits. The income statement of a
 *               period: revenue (credits less debits), cost of goods sold (the COGS account), the other expenses,
 *               gross and net profit. The balance sheet at a day: assets, liabilities and equity at their balances;
 *               no year-end closing journal moves the profit to retained earnings, so the profit is shown as earned:
 *               this year's (from 1 January) and the earlier years' not closed, and assets equal liabilities, equity
 *               and those profits. The general ledger: each account's opening balance, debits, credits and closing
 *               balance over a period, and an account's lines with the balance after each. Accounts with nothing on
 *               them are left out. Pure, unit-tested.
 * </pre>
 */
public final class FinancialStatements {

    private FinancialStatements() {
    }

    /** An account and its amount on the statement (on its normal side: negative when on the other). */
    public record Line(Account account, BigDecimal amount) {
    }

    public record IncomeStatement(LocalDate from, LocalDate to, List<Line> revenue, List<Line> costOfSales, List<Line> expenses) {

        public BigDecimal getTotalRevenue() {
            return sum(revenue);
        }

        public BigDecimal getTotalCostOfSales() {
            return sum(costOfSales);
        }

        public BigDecimal getGrossProfit() {
            return getTotalRevenue().subtract(getTotalCostOfSales());
        }

        public BigDecimal getTotalExpenses() {
            return sum(expenses);
        }

        /** Positive a profit, negative a loss. */
        public BigDecimal getNetProfit() {
            return getGrossProfit().subtract(getTotalExpenses());
        }

        public boolean isEmpty() {
            return revenue.isEmpty() && costOfSales.isEmpty() && expenses.isEmpty();
        }
    }

    public record BalanceSheet(LocalDate asOf, List<Line> assets, List<Line> liabilities, List<Line> equity, BigDecimal earlierProfit,
                               BigDecimal yearProfit) {

        public BigDecimal getTotalAssets() {
            return sum(assets);
        }

        public BigDecimal getTotalLiabilities() {
            return sum(liabilities);
        }

        /** The equity accounts and the profit not closed to them (earlier years' and this year's). */
        public BigDecimal getTotalEquity() {
            return sum(equity).add(earlierProfit).add(yearProfit);
        }

        public BigDecimal getLiabilitiesAndEquity() {
            return getTotalLiabilities().add(getTotalEquity());
        }

        public BigDecimal getDifference() {
            return getTotalAssets().subtract(getLiabilitiesAndEquity());
        }

        public boolean isBalanced() {
            return getDifference().signum() == 0;
        }
    }

    /** An account over a period: its balance before it, the debits and credits in it (balances are debits less credits). */
    public record LedgerRow(Account account, BigDecimal opening, BigDecimal debits, BigDecimal credits) {

        public BigDecimal getClosing() {
            return opening.add(debits).subtract(credits);
        }
    }

    /** A line of an account's ledger and the account's balance after it (debits less credits). */
    public record LedgerLine(JournalLine line, BigDecimal balance) {
    }

    /** The income statement from each account's (id, debits, credits) in the period. */
    public static IncomeStatement incomeStatement(LocalDate from, LocalDate to, Collection<Account> accounts, Collection<Object[]> movements) {
        Map<UUID, BigDecimal[]> sums = sums(movements);
        List<Line> revenue = new ArrayList<>();
        List<Line> costOfSales = new ArrayList<>();
        List<Line> expenses = new ArrayList<>();
        for (Account a : sorted(accounts)) {
            BigDecimal net = net(sums.get(a.getId()));
            if (net.signum() == 0) {
                continue;
            }
            if (a.getType() == AccountType.REVENUE) {
                revenue.add(new Line(a, net.negate()));
            } else if (a.getType() == AccountType.EXPENSE) {
                (a.getSystemKey() == AccountKey.COGS ? costOfSales : expenses).add(new Line(a, net));
            }
        }
        return new IncomeStatement(from, to, revenue, costOfSales, expenses);
    }

    /**
     * The balance sheet from each account's (id, debits, credits) up to the day, and from 1 January of its year to the day
     * (this year's profit); the rest of the revenue and expenses is the earlier years' profit.
     */
    public static BalanceSheet balanceSheet(LocalDate asOf, Collection<Account> accounts, Collection<Object[]> balances,
                                            Collection<Object[]> yearMovements) {
        Map<UUID, BigDecimal[]> all = sums(balances);
        Map<UUID, BigDecimal[]> year = sums(yearMovements);
        List<Line> assets = new ArrayList<>();
        List<Line> liabilities = new ArrayList<>();
        List<Line> equity = new ArrayList<>();
        BigDecimal profit = BigDecimal.ZERO;
        BigDecimal yearProfit = BigDecimal.ZERO;
        for (Account a : sorted(accounts)) {
            BigDecimal net = net(all.get(a.getId()));
            switch (a.getType()) {
                case ASSET -> add(assets, a, net);
                case LIABILITY -> add(liabilities, a, net.negate());
                case EQUITY -> add(equity, a, net.negate());
                default -> {                                    // revenue and expenses: profit is credits less debits
                    profit = profit.subtract(net);
                    yearProfit = yearProfit.subtract(net(year.get(a.getId())));
                }
            }
        }
        return new BalanceSheet(asOf, assets, liabilities, equity, profit.subtract(yearProfit), yearProfit);
    }

    /** Each account with a balance before the period or lines in it, by code. */
    public static List<LedgerRow> ledger(Collection<Account> accounts, Collection<Object[]> before, Collection<Object[]> movements) {
        Map<UUID, BigDecimal[]> opening = sums(before);
        Map<UUID, BigDecimal[]> moved = sums(movements);
        List<LedgerRow> rows = new ArrayList<>();
        for (Account a : sorted(accounts)) {
            BigDecimal open = net(opening.get(a.getId()));
            BigDecimal[] m = moved.getOrDefault(a.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if (open.signum() != 0 || m[0].signum() != 0 || m[1].signum() != 0) {
                rows.add(new LedgerRow(a, open, m[0], m[1]));
            }
        }
        return rows;
    }

    /** An account's lines with the balance after each, from its opening balance. */
    public static List<LedgerLine> running(BigDecimal opening, List<JournalLine> lines) {
        List<LedgerLine> result = new ArrayList<>();
        BigDecimal balance = opening;
        for (JournalLine l : lines) {
            balance = balance.add(l.getDebit()).subtract(l.getCredit());
            result.add(new LedgerLine(l, balance));
        }
        return result;
    }

    // ---------------------------------------------------------------- helpers

    private static void add(List<Line> lines, Account account, BigDecimal amount) {
        if (amount.signum() != 0) {
            lines.add(new Line(account, amount));
        }
    }

    private static BigDecimal sum(List<Line> lines) {
        return lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<UUID, BigDecimal[]> sums(Collection<Object[]> rows) {
        Map<UUID, BigDecimal[]> sums = new HashMap<>();
        for (Object[] r : rows) {
            sums.put((UUID) r[0], new BigDecimal[]{(BigDecimal) r[1], (BigDecimal) r[2]});
        }
        return sums;
    }

    /** Debits less credits. */
    private static BigDecimal net(BigDecimal[] sum) {
        return sum == null ? BigDecimal.ZERO : sum[0].subtract(sum[1]);
    }

    private static List<Account> sorted(Collection<Account> accounts) {
        List<Account> list = new ArrayList<>(accounts);
        list.sort(Comparator.comparing(Account::getCode));
        return list;
    }
}
