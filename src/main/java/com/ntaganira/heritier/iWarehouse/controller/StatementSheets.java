package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.FinancialStatements;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : StatementSheets.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The income statement and the balance sheet as export sheets (ACC-11), shared by their own exports and
 *               the monthly management pack (T.10), so both always show the same figures the same way.
 * </pre>
 */
@Component
public class StatementSheets {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Messages messages;

    public StatementSheets(Messages messages) {
        this.messages = messages;
    }

    /** The income statement of a period, on a sheet of that name. */
    public Excel.Sheet income(String name, FinancialStatements.IncomeStatement is, LocalDate from, LocalDate to) {
        Excel.Builder sheet = Excel.sheet(name, m("fin.income.title"), m("fin.period", from.format(DAY), to.format(DAY)),
                m("fin.code"), m("fin.account"), m("fin.amount"));
        section(sheet, m("fin.revenue"), is.revenue(), m("fin.totalRevenue"), is.getTotalRevenue());
        section(sheet, m("fin.costOfSales"), is.costOfSales(), m("fin.totalCostOfSales"), is.getTotalCostOfSales());
        sheet.bold(null, m("fin.grossProfit"), is.getGrossProfit());
        section(sheet, m("fin.expenses"), is.expenses(), m("fin.totalExpenses"), is.getTotalExpenses());
        sheet.bold(null, is.getNetProfit().signum() < 0 ? m("fin.netLoss") : m("fin.netProfit"), is.getNetProfit());
        return sheet.build();
    }

    /** The balance sheet at a day. */
    public Excel.Sheet balance(String name, FinancialStatements.BalanceSheet bs, LocalDate day) {
        Excel.Builder sheet = Excel.sheet(name, m("fin.balance.title"), m("fin.asOf", day.format(DAY)),
                m("fin.code"), m("fin.account"), m("fin.amount"));
        section(sheet, m("fin.assets"), bs.assets(), m("fin.totalAssets"), bs.getTotalAssets());
        section(sheet, m("fin.liabilities"), bs.liabilities(), m("fin.totalLiabilities"), bs.getTotalLiabilities());
        sheet.bold(m("fin.equity"));
        bs.equity().forEach(l -> sheet.row(l.account().getCode(), l.account().getName(), l.amount()));
        if (bs.earlierProfit().signum() != 0) {
            sheet.row(null, m("fin.earlierProfit"), bs.earlierProfit());
        }
        sheet.row(null, m("fin.yearProfit", String.valueOf(day.getYear())), bs.yearProfit());
        sheet.bold(null, m("fin.totalEquity"), bs.getTotalEquity());
        sheet.bold(null, m("fin.totalLiabilitiesEquity"), bs.getLiabilitiesAndEquity());
        return sheet.build();
    }

    /** A section of a statement: its title, its accounts, its total. */
    private static void section(Excel.Builder sheet, String title, List<FinancialStatements.Line> lines, String total, BigDecimal amount) {
        sheet.bold(null, title);
        lines.forEach(l -> sheet.row(l.account().getCode(), l.account().getName(), l.amount()));
        sheet.bold(null, total, amount);
    }

    private String m(String key, Object... args) {
        return messages.get(key, args);
    }
}
