package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseBillStatus;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.ExpenditureReportService;
import com.ntaganira.heritier.iWarehouse.service.PettyCashService;
import com.ntaganira.heritier.iWarehouse.service.SplitPurchases;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : ExpenditureReportController.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The expenditure report (CMP 10, F-15 A; T.05, T.06, T.14, C.04): spending by cost centre, location and
 *               account, the indicators and split purchases for a period, exported to Excel or PDF. PAGE_EXPENDITURE_REPORT.
 * </pre>
 */
@Controller
public class ExpenditureReportController {

    static final String MODULE = "Reports";
    private static final String AUTH = "hasAuthority('PAGE_EXPENDITURE_REPORT')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ExpenditureReportService service;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public ExpenditureReportController(ExpenditureReportService service, ReportFiles reportFiles, ActivityLogService activityLogService,
                                       Messages messages) {
        this.service = service;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping("/reports/expenditure")
    @PreAuthorize(AUTH)
    public String report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int fpage, Model model) {
        FinancialStatementController.Period p = FinancialStatementController.Period.of(from, to, service.today());
        ExpenditureReportService.Report report = service.report(p.from(), p.to());
        model.addAttribute("report", report);
        model.addAttribute("accountPage", Paging.of(report.byAccount(), Paging.page(page)));
        model.addAttribute("fundPage", Paging.of(report.funds(), Paging.page(fpage)));
        model.addAttribute("statuses", ExpenseBillStatus.values());
        model.addAttribute("from", p.from());
        model.addAttribute("to", p.to());
        model.addAttribute("today", service.today());
        model.addAttribute("query", QueryString.of("from", p.from().toString(), "to", p.to().toString(), "fpage", fpage > 0 ? String.valueOf(fpage) : null));
        model.addAttribute("fundQuery", QueryString.of("from", p.from().toString(), "to", p.to().toString(), "page", page > 0 ? String.valueOf(page) : null));
        return "reports/expenditure";
    }

    @GetMapping("/reports/expenditure/export")
    @PreAuthorize(AUTH)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        FinancialStatementController.Period p = FinancialStatementController.Period.of(from, to, service.today());
        ExpenditureReportService.Report r = service.report(p.from(), p.to());
        String sub = messages.get("fin.period", p.from().format(DAY), p.to().format(DAY));
        Excel.Builder summary = Excel.sheet(messages.get("expenditure.title"), messages.get("expenditure.title"), sub,
                messages.get("stockRecon.check"), messages.get("stockRecon.figure"));
        summary.row(messages.get("expenditure.operating"), r.operating());
        summary.row(messages.get("expenditure.capital"), r.capital());
        summary.row(messages.get("expenditure.untagged"), r.indicators().untagged());
        summary.row(messages.get("expenditure.manual"), r.indicators().manualSpend());
        summary.row(messages.get("expenditure.poCompliance"), r.indicators().getPoCompliance());
        summary.row(messages.get("expenditure.onRegister"), r.indicators().getOnRegisterPercent());
        summary.row(messages.get("expenditure.splits"), r.splits().size());
        summary.row(messages.get("expenditure.toRatify"), r.toRatify());
        summary.row(messages.get("expenditure.advanceRetirement"), r.advances().retirementPercent());
        summary.row(messages.get("expenditure.claimTimeliness"), r.advances().timelinessPercent());
        summary.row(messages.get("expenditure.threeWay"), r.supplierInvoices());
        Excel.Builder centres = rows(messages.get("expenditure.byCentre"), sub, messages.get("expense.line.costCentre"), r.byCentre());
        Excel.Builder places = rows(messages.get("expenditure.byLocation"), sub, messages.get("expense.line.location"), r.byLocation());
        Excel.Builder accounts = rows(messages.get("expenditure.byAccount"), sub, messages.get("fin.account"), r.byAccount());
        Excel.Builder splits = Excel.sheet(messages.get("expenditure.splitsTitle"), messages.get("expenditure.splitsTitle"), sub,
                messages.get("expense.supplier"), messages.get("expenditure.split.documents"), messages.get("expenditure.split.from"),
                messages.get("expenditure.split.to"), messages.get("expenditure.split.total"), messages.get("expenditure.split.threshold"));
        for (SplitPurchases.Flag f : r.splits()) {
            splits.row(f.supplierName(), f.getNumbers(), f.getFrom(), f.getTo(), f.getTotal(), f.threshold());
        }
        Excel.Builder funds = Excel.sheet(messages.get("expenditure.funds"), messages.get("expenditure.funds"), sub, messages.get("pettyCash.fund"),
                messages.get("pettyCash.site"), messages.get("pettyCash.custodian"), messages.get("pettyCash.imprest"), messages.get("pettyCash.balance"),
                messages.get("expenditure.fund.lastCount"), messages.get("expenditure.fund.audited"));
        for (PettyCashService.FundRow f : r.funds()) {
            funds.row(f.fund().getCode(), f.fund().getLocation().getCode(), f.fund().getCustodianName(), f.fund().getImprest(), f.balance(),
                    f.lastDaily() == null ? null : f.lastDaily().getCountedOn(), messages.get(f.auditedThisMonth() ? "common.yes" : "common.no"));
        }
        activityLogService.record(MODULE, "EXPORT_EXPENDITURE", "Exported the expenditure report from " + p.from() + " to " + p.to() + " to "
                + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "expenditure-" + p.from() + "-" + p.to(), summary.build(), centres.build(), places.build(),
                accounts.build(), splits.build(), funds.build());
    }

    private Excel.Builder rows(String title, String sub, String label, List<ExpenditureReportService.Row> rows) {
        Excel.Builder sheet = Excel.sheet(title, title, sub, label, messages.get("expenditure.amount"));
        for (ExpenditureReportService.Row row : rows) {
            sheet.row(row.code().isEmpty() ? messages.get("expenditure.notNamed") : row.code() + (row.name() == null ? "" : " - " + row.name()), row.amount());
        }
        return sheet;
    }
}
