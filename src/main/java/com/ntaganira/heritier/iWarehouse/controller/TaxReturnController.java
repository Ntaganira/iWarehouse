package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.TaxReturnService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : TaxReturnController.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The month's tax returns (U.14): VAT, PAYE and RSSB, the withholding tax return by supplier and the corporate income
 *               tax estimate, due on the 15th of the next month; exported to Excel or PDF. PAGE_TAX_RETURNS; last month by default.
 * </pre>
 */
@Controller
@RequestMapping("/reports/tax")
public class TaxReturnController {

    private static final String MODULE = "Accounting";
    private static final String AUTH = "hasAuthority('PAGE_TAX_RETURNS')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final TaxReturnService service;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public TaxReturnController(TaxReturnService service, ReportFiles reportFiles, ActivityLogService activityLogService, Messages messages) {
        this.service = service;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(AUTH)
    public String report(@RequestParam(required = false) String month, @RequestParam(defaultValue = "0") int page, Model model) {
        List<YearMonth> months = service.months();
        YearMonth chosen = chosen(month, months);
        TaxReturnService.Report report = service.report(chosen);
        model.addAttribute("months", months);
        model.addAttribute("month", chosen);
        model.addAttribute("report", report);
        model.addAttribute("whtLines", Paging.of(report.wht(), Paging.page(page)));
        model.addAttribute("query", QueryString.of("month", chosen.toString()));
        return "reports/tax";
    }

    @GetMapping("/export")
    @PreAuthorize(AUTH)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String month, @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        YearMonth chosen = chosen(month, service.months());
        TaxReturnService.Report r = service.report(chosen);
        String period = messages.get("fin.period", r.getFrom().format(DAY), r.getTo().format(DAY));
        Excel.Builder summary = Excel.sheet(messages.get("taxReturn.summaryTab"), messages.get("taxReturn.title"), period,
                messages.get("taxReturn.tax"), messages.get("taxReturn.amount"), messages.get("taxReturn.due"));
        summary.row(messages.get("taxReturn.vat"), r.vat().getPayable(), r.getDueDate());
        summary.row(messages.get("taxReturn.paye"), r.payRun() == null ? null : r.payRun().getTotalPaye(), r.getDueDate());
        summary.row(messages.get("taxReturn.rssb"), r.payRun() == null ? null : r.payRun().getTotalRssb(), r.getDueDate());
        summary.row(messages.get("taxReturn.wht"), r.getWhtTotal(), r.getDueDate());
        summary.row();
        summary.bold(messages.get("taxReturn.citTitle"), null, null);
        summary.row(messages.get("taxReturn.profitToDate", r.cit().to().format(DAY)), r.cit().profit(), null);
        summary.row(messages.get("taxReturn.citToDate", r.cit().rate().stripTrailingZeros().toPlainString()), r.cit().estimate(), null);
        summary.row(messages.get("taxReturn.yearProfit"), r.cit().yearProfit(), null);
        summary.row(messages.get("taxReturn.yearCit"), r.cit().yearEstimate(), null);
        Excel.Builder wht = Excel.sheet(messages.get("taxReturn.whtTab"), messages.get("taxReturn.whtTitle"), period,
                messages.get("taxReturn.supplier"), messages.get("party.tin"), messages.get("expense.number"), messages.get("expense.supplierRef"),
                messages.get("taxReturn.date"), messages.get("taxReturn.net"), messages.get("taxReturn.rate"), messages.get("taxReturn.withheld"));
        for (TaxReturnService.WhtLine l : r.wht()) {
            wht.row(l.bill().getSupplier().getName(), l.bill().getSupplier().getTin(), l.bill().getNumber() + (l.reversal() ? " (" + messages.get("taxReturn.reversal") + ")" : ""),
                    l.bill().getSupplierRef(), l.getDate(), l.getNet(), l.bill().getWhtRate(), l.getAmount());
        }
        wht.bold(messages.get("fin.total"), null, null, null, null, r.getWhtNet(), null, r.getWhtTotal());
        wht.row(messages.get("taxReturn.whtOwed", r.getTo().format(DAY)), null, null, null, null, null, null, r.whtOwed());
        activityLogService.record(MODULE, "EXPORT_TAX_RETURNS", "Exported the tax returns of " + chosen.format(MONTH) + " to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "tax-returns-" + chosen, summary.build(), wht.build());
    }

    private YearMonth chosen(String month, List<YearMonth> months) {
        YearMonth fallback = months.size() > 1 ? months.get(1) : months.get(0);              // last month: the one to file
        if (!StringUtils.hasText(month)) {
            return fallback;
        }
        try {
            YearMonth m = YearMonth.parse(month.trim());
            return months.contains(m) ? m : fallback;
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
