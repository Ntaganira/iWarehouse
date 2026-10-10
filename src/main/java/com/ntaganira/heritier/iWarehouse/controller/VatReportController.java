package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.VatReport;
import com.ntaganira.heritier.iWarehouse.service.VatReportService;
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
 * - File      : VatReportController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : The monthly VAT report for filing (TAX-05): output VAT per tax letter (sales less credit notes), input VAT
 *               from the ledger, VAT payable, checked against the VAT Output account; on a page, in Excel and in PDF.
 *               PAGE_VAT_REPORT + PERM_VIEW_ACCOUNTING. This month by default.
 * </pre>
 */
@Controller
@RequestMapping("/accounting/vat-report")
public class VatReportController {

    private static final String AUTH = "hasAuthority('PAGE_VAT_REPORT') and hasAuthority('PERM_VIEW_ACCOUNTING')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final VatReportService vatService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final ReportFiles reportFiles;

    public VatReportController(VatReportService vatService, ActivityLogService activityLogService, Messages messages,
                               ReportFiles reportFiles) {
        this.reportFiles = reportFiles;
        this.vatService = vatService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(AUTH)
    public String report(@RequestParam(required = false) String month, @RequestParam(defaultValue = "0") int page, Model model) {
        List<YearMonth> months = vatService.months();
        YearMonth chosen = chosen(month, months);
        VatReportService.Report report = vatService.report(chosen);
        model.addAttribute("months", months);
        model.addAttribute("month", chosen);
        model.addAttribute("report", report);
        model.addAttribute("inputLines", Paging.of(report.inputLines(), Paging.page(page)));
        model.addAttribute("query", QueryString.of("month", chosen.toString()));
        return "statements/vat";
    }

    @GetMapping("/export")
    @PreAuthorize(AUTH)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String month, @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        YearMonth chosen = chosen(month, vatService.months());
        VatReportService.Report r = vatService.report(chosen);
        String period = messages.get("fin.period", r.getFrom().format(DAY), r.getTo().format(DAY));
        Excel.Builder output = Excel.sheet(messages.get("vatReport.title"), messages.get("vatReport.title"), period,
                messages.get("vatReport.letter"), messages.get("vatReport.rate"), messages.get("vatReport.salesGross"), messages.get("vatReport.salesVat"),
                messages.get("vatReport.creditGross"), messages.get("vatReport.creditVat"), messages.get("vatReport.net"), messages.get("vatReport.vat"));
        for (VatReport.Letter l : r.output().letters()) {
            output.row(l.taxCode(), l.rate(), l.salesGross(), l.salesVat(), l.creditGross(), l.creditVat(), l.getNet(), l.getVat());
        }
        output.bold(messages.get("fin.total"), null, r.output().getSalesGross(), null, r.output().getCreditGross(), null, r.output().getNet(),
                r.output().getVat());
        output.row();
        output.bold(messages.get("vatReport.outputVat"), null, null, null, null, null, null, r.getOutputVat());
        output.bold(messages.get("vatReport.inputVat"), null, null, null, null, null, null, r.inputVat());
        output.bold(r.getPayable().signum() < 0 ? messages.get("vatReport.credit") : messages.get("vatReport.payable"), null, null, null, null, null, null,
                r.getPayable().abs());
        output.row(messages.get("vatReport.ledgerOutput"), null, null, null, null, null, null, r.ledgerOutputVat());
        Excel.Builder input = Excel.sheet(messages.get("vatReport.inputVat"), messages.get("vatReport.inputVat"), period,
                messages.get("fin.date"), messages.get("fin.journal"), messages.get("fin.description"), messages.get("fin.about"), messages.get("fin.amount"));
        for (JournalLine l : r.inputLines()) {
            input.row(l.getEntry().getEntryDate(), l.getEntry().getNumber(), l.getEntry().getDescription(),
                    FinancialStatementController.about(l), l.getDebit().subtract(l.getCredit()));
        }
        input.bold(null, null, null, messages.get("fin.total"), r.inputVat());
        activityLogService.record(AccountingController.MODULE, "EXPORT_VAT_REPORT", "Exported the VAT report of " + chosen + " to " + format.label(),
                ActivityStatus.SUCCESS);
        return reportFiles.download(format, "vat-report-" + chosen, output.build(), input.build());
    }

    /** The month asked for when it is one to choose from, else this month. */
    private static YearMonth chosen(String month, List<YearMonth> months) {
        if (StringUtils.hasText(month)) {
            try {
                YearMonth m = YearMonth.parse(month.trim());
                if (months.contains(m)) {
                    return m;
                }
            } catch (DateTimeParseException e) {
                // not a month: this month
            }
        }
        return months.get(0);
    }
}
