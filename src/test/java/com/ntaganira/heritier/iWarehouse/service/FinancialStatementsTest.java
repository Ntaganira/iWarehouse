package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.AccountType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The financial statements (ACC-11): income statement sections and profit, a balance sheet that balances with the profit
 * not closed, and the general ledger's opening, movements, closing and running balance.
 */
class FinancialStatementsTest {

    private final Account bank = account("1030", "Bank", AccountType.ASSET, AccountKey.BANK);
    private final Account inventory = account("1200", "Inventory - Glass", AccountType.ASSET, AccountKey.INVENTORY);
    private final Account payable = account("2010", "Accounts Payable", AccountType.LIABILITY, AccountKey.PAYABLE);
    private final Account vat = account("2050", "VAT Output Payable", AccountType.LIABILITY, AccountKey.VAT_OUTPUT);
    private final Account capital = account("3010", "Owner's Capital", AccountType.EQUITY, null);
    private final Account sales = account("4010", "Sales Revenue", AccountType.REVENUE, AccountKey.SALES);
    private final Account returns = account("4020", "Sales Returns", AccountType.REVENUE, AccountKey.SALES_RETURNS);
    private final Account cogs = account("5010", "Cost of Goods Sold", AccountType.EXPENSE, AccountKey.COGS);
    private final Account spoilage = account("5020", "Glass Spoilage Expense", AccountType.EXPENSE, AccountKey.SPOILAGE);
    private final Account rent = account("5190", "Office rent", AccountType.EXPENSE, null);
    private final List<Account> accounts = List.of(rent, bank, inventory, payable, vat, capital, sales, returns, cogs, spoilage);

    @Test
    void theIncomeStatementSeparatesRevenueCostOfSalesAndExpenses() {
        FinancialStatements.IncomeStatement is = FinancialStatements.incomeStatement(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), accounts,
                List.of(row(sales, "0", "1000000"), row(returns, "50000", "0"), row(cogs, "600000", "0"), row(spoilage, "20000", "0"),
                        row(rent, "150000", "0"), row(bank, "1000000", "800000")));

        assertThat(is.revenue()).extracting(l -> l.account().getCode() + " " + l.amount().toPlainString())
                .containsExactly("4010 1000000", "4020 -50000");                         // returns reduce revenue
        assertThat(is.getTotalRevenue()).isEqualByComparingTo("950000");
        assertThat(is.getTotalCostOfSales()).isEqualByComparingTo("600000");
        assertThat(is.getGrossProfit()).isEqualByComparingTo("350000");
        assertThat(is.expenses()).extracting(l -> l.account().getCode()).containsExactly("5020", "5190");   // by code; no bank
        assertThat(is.getNetProfit()).isEqualByComparingTo("180000");
        assertThat(FinancialStatements.incomeStatement(null, null, accounts, List.of()).isEmpty()).isTrue();
    }

    @Test
    void theBalanceSheetBalancesWithThisYearsAndEarlierYearsProfit() {
        // Up to 09/10/2026: capital 5,000,000; last year a profit of 200,000; this year sales 1,000,000 less cost 600,000
        List<Object[]> balances = List.of(row(bank, "6300000", "500000"), row(inventory, "1000000", "600000"), row(payable, "0", "300000"),
                row(vat, "0", "0"), row(capital, "0", "5000000"), row(sales, "0", "1500000"), row(cogs, "900000", "0"), row(rent, "100000", "0"));
        List<Object[]> year = List.of(row(sales, "0", "1000000"), row(cogs, "600000", "0"));

        FinancialStatements.BalanceSheet bs = FinancialStatements.balanceSheet(LocalDate.of(2026, 10, 9), accounts, balances, year);

        assertThat(bs.assets()).extracting(l -> l.account().getCode() + " " + l.amount().toPlainString())
                .containsExactly("1030 5800000", "1200 400000");
        assertThat(bs.liabilities()).extracting(l -> l.account().getCode()).containsExactly("2010");   // VAT at 0 left out
        assertThat(bs.yearProfit()).isEqualByComparingTo("400000");
        assertThat(bs.earlierProfit()).isEqualByComparingTo("100000");                // 500,000 - 300,000 - 100,000 in all, less this year
        assertThat(bs.getTotalAssets()).isEqualByComparingTo("6200000");
        assertThat(bs.getTotalEquity()).isEqualByComparingTo("5500000");
        assertThat(bs.getLiabilitiesAndEquity()).isEqualByComparingTo("5800000");
        assertThat(bs.getDifference()).isEqualByComparingTo("400000");                 // the rows above do not balance on purpose
        assertThat(bs.isBalanced()).isFalse();

        // A balanced ledger: every journal balances, so assets = liabilities + equity + profit
        List<Object[]> even = List.of(row(bank, "5000000", "0"), row(capital, "0", "5000000"), row(bank, "0", "0"));
        assertThat(FinancialStatements.balanceSheet(LocalDate.of(2026, 10, 9), accounts,
                List.of(row(bank, "5600000", "0"), row(capital, "0", "5000000"), row(sales, "0", "1000000"), row(cogs, "400000", "0")),
                List.of(row(sales, "0", "1000000"), row(cogs, "400000", "0"))).isBalanced()).isTrue();
        assertThat(even).isNotEmpty();
    }

    @Test
    void theGeneralLedgerGivesOpeningMovementsClosingAndTheBalanceAfterEachLine() {
        List<FinancialStatements.LedgerRow> rows = FinancialStatements.ledger(accounts,
                List.of(row(bank, "500000", "100000"), row(capital, "0", "0")),
                List.of(row(bank, "200000", "50000"), row(sales, "0", "200000")));
        assertThat(rows).extracting(r -> r.account().getCode()).containsExactly("1030", "4010");     // capital has nothing
        FinancialStatements.LedgerRow b = rows.get(0);
        assertThat(b.opening()).isEqualByComparingTo("400000");
        assertThat(b.getClosing()).isEqualByComparingTo("550000");
        assertThat(rows.get(1).getClosing()).isEqualByComparingTo("-200000");          // a credit balance

        List<FinancialStatements.LedgerLine> lines = FinancialStatements.running(new BigDecimal("400000"),
                List.of(line("200000", "0"), line("0", "50000")));
        assertThat(lines).extracting(l -> l.balance().toPlainString()).containsExactly("600000", "550000");
    }

    private static Account account(String code, String name, AccountType type, AccountKey key) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setCode(code);
        a.setName(name);
        a.setType(type);
        a.setSystemKey(key);
        return a;
    }

    private static Object[] row(Account account, String debits, String credits) {
        return new Object[]{account.getId(), new BigDecimal(debits), new BigDecimal(credits)};
    }

    private static JournalLine line(String debit, String credit) {
        JournalLine l = new JournalLine();
        l.setDebit(new BigDecimal(debit));
        l.setCredit(new BigDecimal(credit));
        return l;
    }
}
