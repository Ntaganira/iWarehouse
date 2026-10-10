package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.FinancialStatementService;
import com.ntaganira.heritier.iWarehouse.service.FinancialStatements;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : FinancialStatementController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : The financial statements (ACC-11): the income statement of a period, the balance sheet at a day and
 *               the general ledger of a period (every account, or one account's lines), each on a page and exported to
 *               Excel or PDF (ReportFiles). PAGE_FINANCIAL_STATEMENTS + PERM_VIEW_ACCOUNTING. Periods default to this month up to today.
 * </pre>
 */
@Controller
@RequestMapping("/accounting")
public class FinancialStatementController {

    private static final String AUTH = "hasAuthority('PAGE_FINANCIAL_STATEMENTS') and hasAuthority('PERM_VIEW_ACCOUNTING')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final FinancialStatementService statementService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final ReportFiles reportFiles;

    public FinancialStatementController(FinancialStatementService statementService, ActivityLogService activityLogService, Messages messages,
                                        ReportFiles reportFiles) {
        this.statementService = statementService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.reportFiles = reportFiles;
    }

    /** A period: up to today, from the first day of its month by default; a start after the end starts the end's month. */
    record Period(LocalDate from, LocalDate to) {

        static Period of(LocalDate from, LocalDate to, LocalDate today) {
            LocalDate end = to == null || to.isAfter(today) ? today : to;
            LocalDate start = from == null || from.isAfter(end) ? end.withDayOfMonth(1) : from;
            return new Period(start, end);
        }
    }

    // ---------------------------------------------------------------- income statement

    @GetMapping("/income-statement")
    @PreAuthorize(AUTH)
    public String incomeStatement(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, Model model) {
        Period p = Period.of(from, to, statementService.today());
        model.addAttribute("income", statementService.incomeStatement(p.from(), p.to()));
        model.addAttribute("from", p.from());
        model.addAttribute("to", p.to());
        model.addAttribute("today", statementService.today());
        model.addAttribute("tab", "income");
        return "statements/income";
    }

    @GetMapping("/income-statement/export")
    @PreAuthorize(AUTH)
    public ResponseEntity<byte[]> incomeStatementExport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                        @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        Period p = Period.of(from, to, statementService.today());
        FinancialStatements.IncomeStatement is = statementService.incomeStatement(p.from(), p.to());
        Excel.Builder sheet = Excel.sheet(m("fin.income.title"), m("fin.income.title"), m("fin.period", p.from().format(DAY), p.to().format(DAY)),
                m("fin.code"), m("fin.account"), m("fin.amount"));
        section(sheet, m("fin.revenue"), is.revenue(), m("fin.totalRevenue"), is.getTotalRevenue());
        section(sheet, m("fin.costOfSales"), is.costOfSales(), m("fin.totalCostOfSales"), is.getTotalCostOfSales());
        sheet.bold(null, m("fin.grossProfit"), is.getGrossProfit());
        section(sheet, m("fin.expenses"), is.expenses(), m("fin.totalExpenses"), is.getTotalExpenses());
        sheet.bold(null, is.getNetProfit().signum() < 0 ? m("fin.netLoss") : m("fin.netProfit"), is.getNetProfit());
        return file(format, "EXPORT_INCOME_STATEMENT", "income statement from " + p.from() + " to " + p.to(),
                "income-statement-" + p.from() + "-" + p.to(), sheet.build());
    }

    // ---------------------------------------------------------------- balance sheet

    @GetMapping("/balance-sheet")
    @PreAuthorize(AUTH)
    public String balanceSheet(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf, Model model) {
        LocalDate day = day(asOf);
        model.addAttribute("bs", statementService.balanceSheet(day));
        model.addAttribute("asOf", day);
        model.addAttribute("today", statementService.today());
        model.addAttribute("tab", "balance");
        return "statements/balance";
    }

    @GetMapping("/balance-sheet/export")
    @PreAuthorize(AUTH)
    public ResponseEntity<byte[]> balanceSheetExport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                                     @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        LocalDate day = day(asOf);
        FinancialStatements.BalanceSheet bs = statementService.balanceSheet(day);
        Excel.Builder sheet = Excel.sheet(m("fin.balance.title"), m("fin.balance.title"), m("fin.asOf", day.format(DAY)),
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
        return file(format, "EXPORT_BALANCE_SHEET", "balance sheet as at " + day, "balance-sheet-" + day, sheet.build());
    }

    // ---------------------------------------------------------------- general ledger

    @GetMapping("/general-ledger")
    @PreAuthorize(AUTH)
    public String generalLedger(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(required = false) UUID account, @RequestParam(defaultValue = "0") int page, Model model) {
        Period p = Period.of(from, to, statementService.today());
        model.addAttribute("from", p.from());
        model.addAttribute("to", p.to());
        model.addAttribute("today", statementService.today());
        model.addAttribute("accounts", statementService.accounts());
        model.addAttribute("account", account);
        model.addAttribute("tab", "ledger");
        if (account != null) {
            FinancialStatementService.AccountLedger ledger = statementService.accountLedger(account, p.from(), p.to());
            model.addAttribute("ledger", ledger);
            model.addAttribute("lines", Paging.of(ledger.lines(), Paging.page(page)));
        } else {
            List<FinancialStatements.LedgerRow> rows = statementService.ledger(p.from(), p.to());
            model.addAttribute("rows", Paging.of(rows, Paging.page(page)));
            model.addAttribute("debits", rows.stream().map(FinancialStatements.LedgerRow::debits).reduce(BigDecimal.ZERO, BigDecimal::add));
            model.addAttribute("credits", rows.stream().map(FinancialStatements.LedgerRow::credits).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        model.addAttribute("query", QueryString.of("from", p.from().toString(), "to", p.to().toString(),
                "account", account == null ? null : account.toString()));
        return "statements/ledger";
    }

    @GetMapping("/general-ledger/export")
    @PreAuthorize(AUTH)
    public ResponseEntity<byte[]> generalLedgerExport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      @RequestParam(required = false) UUID account, @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        Period p = Period.of(from, to, statementService.today());
        String period = m("fin.period", p.from().format(DAY), p.to().format(DAY));
        if (account != null) {
            FinancialStatementService.AccountLedger ledger = statementService.accountLedger(account, p.from(), p.to());
            Account a = ledger.row().account();
            Excel.Builder sheet = Excel.sheet(a.getCode(), m("fin.ledger.title") + " · " + a.getCode() + " " + a.getName(), period,
                    m("fin.date"), m("fin.journal"), m("fin.event"), m("fin.document"), m("fin.about"), m("fin.debit"), m("fin.credit"), m("fin.balance"));
            sheet.bold(null, null, m("fin.opening"), null, null, null, null, ledger.row().opening());
            for (FinancialStatements.LedgerLine l : ledger.lines()) {
                JournalLine line = l.line();
                sheet.row(line.getEntry().getEntryDate(), line.getEntry().getNumber(), m("journal.source." + line.getEntry().getSourceType()),
                        line.getEntry().getSourceNumber(), about(line), nonZero(line.getDebit()), nonZero(line.getCredit()), l.balance());
            }
            sheet.bold(null, null, m("fin.closing"), null, null, ledger.row().debits(), ledger.row().credits(), ledger.row().getClosing());
            return file(format, "EXPORT_GENERAL_LEDGER", "general ledger of " + a.getCode() + " from " + p.from() + " to " + p.to(),
                    "general-ledger-" + a.getCode() + "-" + p.from() + "-" + p.to(), sheet.build());
        }
        List<FinancialStatements.LedgerRow> rows = statementService.ledger(p.from(), p.to());
        Excel.Builder sheet = Excel.sheet(m("fin.ledger.title"), m("fin.ledger.title"), period,
                m("fin.code"), m("fin.account"), m("fin.opening"), m("fin.debits"), m("fin.credits"), m("fin.closing"));
        for (FinancialStatements.LedgerRow r : rows) {
            sheet.row(r.account().getCode(), r.account().getName(), r.opening(), r.debits(), r.credits(), r.getClosing());
        }
        sheet.bold(null, m("fin.total"), null, rows.stream().map(FinancialStatements.LedgerRow::debits).reduce(BigDecimal.ZERO, BigDecimal::add),
                rows.stream().map(FinancialStatements.LedgerRow::credits).reduce(BigDecimal.ZERO, BigDecimal::add), null);
        return file(format, "EXPORT_GENERAL_LEDGER", "general ledger from " + p.from() + " to " + p.to(),
                "general-ledger-" + p.from() + "-" + p.to(), sheet.build());
    }

    // ---------------------------------------------------------------- helpers

    /** A section of a statement: its title, its accounts, its total. */
    private static void section(Excel.Builder sheet, String title, List<FinancialStatements.Line> lines, String total, BigDecimal amount) {
        sheet.bold(null, title);
        lines.forEach(l -> sheet.row(l.account().getCode(), l.account().getName(), l.amount()));
        sheet.bold(null, total, amount);
    }

    /** "CLR-6 · Shandong Float Glass Co. · INV-778": what a ledger line is about. */
    static String about(JournalLine line) {
        StringBuilder s = new StringBuilder();
        if (line.getProduct() != null) {
            s.append(line.getProduct().getCode());
        }
        if (line.getSupplier() != null) {
            s.append(s.isEmpty() ? "" : " · ").append(line.getSupplier().getName());
        }
        if (line.getCustomer() != null) {
            s.append(s.isEmpty() ? "" : " · ").append(line.getCustomer().getName());
        }
        if (line.getMemo() != null) {
            s.append(s.isEmpty() ? "" : " · ").append(line.getMemo());
        }
        return s.toString();
    }

    private static BigDecimal nonZero(BigDecimal amount) {
        return amount.signum() == 0 ? null : amount;
    }

    private LocalDate day(LocalDate asOf) {
        LocalDate today = statementService.today();
        return asOf == null || asOf.isAfter(today) ? today : asOf;
    }

    private String m(String key, Object... args) {
        return messages.get(key, args);
    }

    /** The sheet as a download, logged. */
    private ResponseEntity<byte[]> file(ReportFiles.Format format, String action, String what, String name, Excel.Sheet sheet) {
        activityLogService.record(AccountingController.MODULE, action, "Exported the " + what + " to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, name, sheet);
    }
}
