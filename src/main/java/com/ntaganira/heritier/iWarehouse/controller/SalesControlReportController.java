package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : SalesControlReportController.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The discount analysis (SDP 15-16; R.06, M.15: lines charged below their list price, by seller, customer,
 *               glass or invoice) and collection performance (BP 5.1, Annex D; Q.05: by territory, salesperson or
 *               customer), each for a period and exported to Excel or PDF. PAGE_DISCOUNTS_REPORT, PAGE_COLLECTIONS_REPORT.
 * </pre>
 */
@Controller
public class SalesControlReportController {

    static final String MODULE = "Reports";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SalesControlReportService service;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final AgeingSheets ageingSheets;

    public SalesControlReportController(SalesControlReportService service, ReportFiles reportFiles, ActivityLogService activityLogService,
                                        Messages messages, AgeingSheets ageingSheets) {
        this.ageingSheets = ageingSheets;
        this.service = service;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    // ---------------------------------------------------------------- discounts (R.06, M.15)

    @GetMapping("/reports/discounts")
    @PreAuthorize("hasAuthority('PAGE_DISCOUNTS_REPORT')")
    public String discounts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(defaultValue = "SELLER") DiscountAnalysis.GroupBy by, @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "0") int lpage, Model model) {
        FinancialStatementController.Period p = FinancialStatementController.Period.of(from, to, service.today());
        SalesControlReportService.Discounts report = service.discounts(p.from(), p.to(), by);
        model.addAttribute("report", report);
        model.addAttribute("rowPage", Paging.of(report.rows(), Paging.page(page)));
        model.addAttribute("linePage", Paging.of(report.lines(), Paging.page(lpage)));
        model.addAttribute("from", p.from());
        model.addAttribute("to", p.to());
        model.addAttribute("today", service.today());
        model.addAttribute("by", by);
        model.addAttribute("groupings", DiscountAnalysis.GroupBy.values());
        model.addAttribute("query", QueryString.of("from", p.from().toString(), "to", p.to().toString(), "by", by.name()));
        return "reports/discounts";
    }

    @GetMapping("/reports/discounts/export")
    @PreAuthorize("hasAuthority('PAGE_DISCOUNTS_REPORT')")
    public ResponseEntity<byte[]> discountsExport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(defaultValue = "SELLER") DiscountAnalysis.GroupBy by,
                                                  @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        FinancialStatementController.Period p = FinancialStatementController.Period.of(from, to, service.today());
        SalesControlReportService.Discounts r = service.discounts(p.from(), p.to(), by);
        String sub = messages.get("fin.period", p.from().format(DAY), p.to().format(DAY));
        Excel.Builder groups = Excel.sheet(messages.get("discounts.by." + by), messages.get("discounts.title"), sub, messages.get("discounts.group." + by),
                messages.get("discounts.lines"), messages.get("discounts.invoices"), messages.get("discounts.listAmount"), messages.get("discounts.charged"),
                messages.get("discounts.discount"), messages.get("discounts.percent"));
        for (DiscountAnalysis.Row row : r.rows()) {
            groups.row(label(row.label()), row.lines(), row.invoices(), row.listAmount(), row.amount(), row.getDiscount(), row.getPercent());
        }
        groups.bold(messages.get("cutting.total"), r.total().lines(), r.total().invoices(), r.total().listAmount(), r.total().amount(),
                r.total().getDiscount(), r.total().getPercent());
        groups.row(messages.get("discounts.sales"), null, null, null, r.sales(), null, null);
        groups.row(messages.get("discounts.share"), null, null, null, null, null, r.getSharePercent());
        Excel.Builder lines = Excel.sheet(messages.get("discounts.linesTitle"), messages.get("discounts.linesTitle"), sub,
                messages.get("invoice.number"), messages.get("invoice.date"), messages.get("discounts.group.SELLER"), messages.get("invoice.customer"),
                messages.get("discounts.group.PRODUCT"), messages.get("discounts.listAmount"), messages.get("discounts.charged"),
                messages.get("discounts.discount"), messages.get("discounts.reason"));
        for (DiscountAnalysis.Line l : r.lines()) {
            lines.row(l.invoiceNumber(), l.day(), label(l.sellerName()), l.customerName(), l.item(), l.listAmount(), l.amount(), l.getDiscount(),
                    l.reason());
        }
        activityLogService.record(MODULE, "EXPORT_DISCOUNTS", "Exported the discount analysis from " + p.from() + " to " + p.to() + " by "
                + by.name().toLowerCase() + " to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "discounts-" + p.from() + "-" + p.to(), groups.build(), lines.build());
    }

    // ---------------------------------------------------------------- collections (Q.05)

    @GetMapping("/reports/collections")
    @PreAuthorize("hasAuthority('PAGE_COLLECTIONS_REPORT')")
    public String collections(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              @RequestParam(defaultValue = "TERRITORY") CollectionAnalysis.GroupBy by, @RequestParam(defaultValue = "0") int page,
                              Model model) {
        FinancialStatementController.Period p = FinancialStatementController.Period.of(from, to, service.today());
        SalesControlReportService.Collections report = service.collections(p.from(), p.to(), by);
        model.addAttribute("report", report);
        model.addAttribute("rowPage", Paging.of(report.rows(), Paging.page(page)));
        model.addAttribute("from", p.from());
        model.addAttribute("to", p.to());
        model.addAttribute("today", service.today());
        model.addAttribute("by", by);
        model.addAttribute("groupings", CollectionAnalysis.GroupBy.values());
        model.addAttribute("query", QueryString.of("from", p.from().toString(), "to", p.to().toString(), "by", by.name()));
        return "reports/collections";
    }

    @GetMapping("/reports/collections/export")
    @PreAuthorize("hasAuthority('PAGE_COLLECTIONS_REPORT')")
    public ResponseEntity<byte[]> collectionsExport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                    @RequestParam(defaultValue = "TERRITORY") CollectionAnalysis.GroupBy by,
                                                    @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        FinancialStatementController.Period p = FinancialStatementController.Period.of(from, to, service.today());
        SalesControlReportService.Collections r = service.collections(p.from(), p.to(), by);
        Excel.Sheet sheet = ageingSheets.collections(r, messages.get("collections.by." + by));
        activityLogService.record(MODULE, "EXPORT_COLLECTIONS", "Exported collection performance from " + p.from() + " to " + p.to() + " by "
                + by.name().toLowerCase() + " to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "collections-" + p.from() + "-" + p.to(), sheet);
    }

    private String label(String label) {
        return label == null || label.isBlank() ? messages.get("reports.none") : label;
    }
}
