package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.BudgetReview;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.BudgetReportService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : BudgetReportController.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The budget against actual (CMP 04, 10; Annex D): a month of the approved budget's year, its lines and what
 *               no line covers, the COO's and supply chain's indicators, its sign-off (PERM_REVIEW_BUDGET), exported to
 *               Excel or PDF. Needs PAGE_BUDGET_REPORT.
 * </pre>
 */
@Controller
public class BudgetReportController {

    private static final String MODULE = "Budget";
    private static final String AUTH = "hasAuthority('PAGE_BUDGET_REPORT')";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final BudgetReportService service;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public BudgetReportController(BudgetReportService service, ReportFiles reportFiles, ActivityLogService activityLogService, Messages messages) {
        this.service = service;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping("/reports/budget")
    @PreAuthorize(AUTH)
    public String report(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int upage,
                         @RequestParam(defaultValue = "0") int cpage, Model model) {
        YearMonth ym = period(year, month);
        Optional<BudgetReportService.Report> report = service.report(ym.getYear(), ym.getMonthValue());
        model.addAttribute("report", report.orElse(null));
        report.ifPresent(r -> {
            model.addAttribute("rowPage", Paging.of(r.rows(), Paging.page(page)));
            model.addAttribute("unbudgetedPage", Paging.of(r.unbudgeted(), Paging.page(upage)));
            model.addAttribute("consignmentPage", Paging.of(r.supplyChain().consignments(), Paging.page(cpage)));
        });
        model.addAttribute("year", ym.getYear());
        model.addAttribute("month", ym.getMonthValue());
        model.addAttribute("today", service.today());
        String y = String.valueOf(ym.getYear()), m = String.valueOf(ym.getMonthValue());
        model.addAttribute("query", QueryString.of("year", y, "month", m, "upage", upage > 0 ? String.valueOf(upage) : null,
                "cpage", cpage > 0 ? String.valueOf(cpage) : null));
        model.addAttribute("unbudgetedQuery", QueryString.of("year", y, "month", m, "page", page > 0 ? String.valueOf(page) : null,
                "cpage", cpage > 0 ? String.valueOf(cpage) : null));
        model.addAttribute("consignmentQuery", QueryString.of("year", y, "month", m, "page", page > 0 ? String.valueOf(page) : null,
                "upage", upage > 0 ? String.valueOf(upage) : null));
        model.addAttribute("monthStarts", com.ntaganira.heritier.iWarehouse.service.Budgets.monthStarts(ym.getYear()));
        return "reports/budget";
    }

    @PostMapping("/reports/budget/sign-off")
    @PreAuthorize(AUTH + " and hasAuthority('PERM_REVIEW_BUDGET')")
    public String signOff(@RequestParam int year, @RequestParam int month, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        String label = YearMonth.of(year, month).format(MONTH);
        try {
            BudgetReview review = service.signOff(year, month, note);
            activityLogService.record(MODULE, "APPROVE_BUDGET_REVIEW", "Signed off the budget against actual of " + label + " (" + review.getFlagged()
                    + " flagged)" + (review.getNote() == null ? "" : ": " + review.getNote()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("budgetReport.signedOff", label));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "APPROVE_BUDGET_REVIEW", "Failed to sign off the budget against actual of " + label + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/reports/budget?year=" + year + "&month=" + month;
    }

    @GetMapping("/reports/budget/export")
    @PreAuthorize(AUTH)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month,
                                         @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        YearMonth ym = period(year, month);
        BudgetReportService.Report r = service.report(ym.getYear(), ym.getMonthValue())
                .orElseThrow(() -> new com.ntaganira.heritier.iWarehouse.exception.NotFoundException("Budget", String.valueOf(ym.getYear())));
        String sub = messages.get("budgetReport.subtitle", ym.format(MONTH));
        Excel.Builder lines = Excel.sheet(messages.get("budgetReport.title"), messages.get("budgetReport.title"), sub,
                messages.get("expense.line.costCentre"), messages.get("fin.account"), messages.get("budgetReport.monthBudget"),
                messages.get("budgetReport.monthActual"), messages.get("budgetReport.variance"), messages.get("budgetReport.ytdBudget"),
                messages.get("budgetReport.ytdActual"), messages.get("budgetReport.variance"), messages.get("budgetReport.variancePercent"),
                messages.get("budgetReport.flagged"));
        for (BudgetReportService.Row row : r.rows()) {
            lines.row(row.centreCode() == null ? messages.get("budget.company") : row.centreCode(), row.accountCode() + " " + row.accountName(),
                    row.month().budget(), row.month().actual(), row.month().getAmount(), row.toDate().budget(), row.toDate().actual(),
                    row.toDate().getAmount(), row.toDate().getPercent(), row.flagged() ? messages.get("common.yes") : null);
        }
        Excel.Builder unbudgeted = Excel.sheet(messages.get("budgetReport.unbudgeted"), messages.get("budgetReport.unbudgeted"), sub,
                messages.get("expense.line.costCentre"), messages.get("fin.account"), messages.get("budgetReport.monthActual"), messages.get("budgetReport.ytdActual"));
        for (BudgetReportService.Unbudgeted u : r.unbudgeted()) {
            unbudgeted.row(u.centreCode() == null ? messages.get("budget.company") : u.centreCode(), u.accountCode() + " " + u.accountName(), u.month(), u.toDate());
        }
        Excel.Builder indicators = Excel.sheet(messages.get("budgetReport.indicators"), messages.get("budgetReport.indicators"), sub,
                messages.get("stockRecon.check"), messages.get("budgetReport.ytdBudget"), messages.get("budgetReport.ytdActual"), messages.get("budgetReport.variancePercent"));
        indicators.row(messages.get("budgetReport.turnover"), r.coo().turnover().budget(), r.coo().turnover().actual(), r.coo().turnover().getPercent());
        indicators.row(messages.get("budgetReport.operatingCost"), r.coo().operatingCost().budget(), r.coo().operatingCost().actual(),
                r.coo().operatingCost().getPercent());
        indicators.row(messages.get("budgetReport.margin"), r.coo().marginTarget(), r.coo().marginPercent(), null);
        indicators.row(messages.get("budgetReport.landedCost"), r.supplyChain().landedCost().budget(), r.supplyChain().landedCost().actual(),
                r.supplyChain().landedCost().getPercent());
        indicators.row(messages.get("budgetReport.countAccuracy"), null, r.supplyChain().countAccuracy(), null);
        Excel.Builder consignments = Excel.sheet(messages.get("budgetReport.consignments"), messages.get("budgetReport.consignments"), sub,
                messages.get("budgetReport.consignment"), messages.get("budgetReport.demurrage"), messages.get("budgetReport.storage"),
                messages.get("budgetReport.consignmentTotal"), messages.get("budgetReport.ceiling"));
        for (BudgetReportService.Consignment c : r.supplyChain().consignments()) {
            consignments.row(c.number(), c.demurrage(), c.storage(), c.getTotal(), c.ceiling());
        }
        activityLogService.record(MODULE, "EXPORT_BUDGET_REPORT", "Exported the budget against actual of " + ym.format(MONTH) + " to " + format.label(),
                ActivityStatus.SUCCESS);
        return reportFiles.download(format, "budget-" + ym, lines.build(), unbudgeted.build(), indicators.build(), consignments.build());
    }

    private YearMonth period(Integer year, Integer month) {
        YearMonth def = service.defaultMonth();
        int y = year == null ? def.getYear() : year;
        int m = month == null ? (year == null ? def.getMonthValue() : 12) : Math.max(1, Math.min(12, month));
        return YearMonth.of(y, m);
    }
}
