package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.SalesAnalysis;
import com.ntaganira.heritier.iWarehouse.service.SalesReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : ReportController.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The reports page (every report the user may open, PAGE_REPORTS) and the sales reports (RPT-05): the
 *               invoices issued in a period less their credit notes, by customer, glass or processing, salesperson, day,
 *               month or invoice, with the gross margin at MAC; on a page and in Excel or PDF (RPT-07).
 *               PAGE_SALES_REPORTS + PERM_VIEW_SALES_REPORTS; cost and margin with PERM_VIEW_STOCK_COST.
 * </pre>
 */
@Controller
@RequestMapping("/reports")
public class ReportController {

    static final String MODULE = "Reports";
    private static final String SALES = "hasAuthority('PAGE_SALES_REPORTS') and hasAuthority('PERM_VIEW_SALES_REPORTS')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SalesReportService salesReports;
    private final ActivityLogService activityLogService;
    private final ReportFiles reportFiles;
    private final Messages messages;

    public ReportController(SalesReportService salesReports, ActivityLogService activityLogService, ReportFiles reportFiles, Messages messages) {
        this.salesReports = salesReports;
        this.activityLogService = activityLogService;
        this.reportFiles = reportFiles;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_REPORTS')")
    public String index() {
        return "reports/index";
    }

    // ---------------------------------------------------------------- sales (RPT-05)

    @GetMapping("/sales")
    @PreAuthorize(SALES)
    public String sales(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(defaultValue = "CUSTOMER") SalesAnalysis.GroupBy group,
                        @RequestParam(required = false) UUID customer, @RequestParam(required = false) String item,
                        @RequestParam(required = false) String salesperson, @RequestParam(defaultValue = "0") int page, Model model) {
        FinancialStatementController.Period p = FinancialStatementController.Period.of(from, to, salesReports.today());
        SalesReportService.Report report = salesReports.report(p.from(), p.to(), group, customer, blank(item), blank(salesperson));
        model.addAttribute("report", report);
        model.addAttribute("rows", Paging.of(report.rows(), Paging.page(page)));
        model.addAttribute("groups", SalesAnalysis.GroupBy.values());
        model.addAttribute("group", group);
        model.addAttribute("from", p.from());
        model.addAttribute("to", p.to());
        model.addAttribute("today", salesReports.today());
        model.addAttribute("customer", customer == null ? null : customer.toString());
        model.addAttribute("item", blank(item));
        model.addAttribute("salesperson", blank(salesperson));
        model.addAttribute("query", QueryString.of("from", p.from().toString(), "to", p.to().toString(), "group", group.name(),
                "customer", customer == null ? null : customer.toString(), "item", blank(item), "salesperson", blank(salesperson)));
        return "reports/sales";
    }

    @GetMapping("/sales/export")
    @PreAuthorize(SALES)
    public ResponseEntity<byte[]> salesExport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(defaultValue = "CUSTOMER") SalesAnalysis.GroupBy group,
                                              @RequestParam(required = false) UUID customer, @RequestParam(required = false) String item,
                                              @RequestParam(required = false) String salesperson,
                                              @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        boolean seeCost = AppUserPrincipal.currentHas("PERM_VIEW_STOCK_COST");
        FinancialStatementController.Period p = FinancialStatementController.Period.of(from, to, salesReports.today());
        SalesReportService.Report report = salesReports.report(p.from(), p.to(), group, customer, blank(item), blank(salesperson));
        List<String> headers = new ArrayList<>();
        if (group == SalesAnalysis.GroupBy.INVOICE) {
            headers.addAll(List.of(m("salesReport.invoice"), m("salesReport.date"), m("salesReport.customer"), m("salesReport.salesperson")));
        } else {
            headers.add(m("salesReport.group." + group));
            headers.add(m("salesReport.invoices"));
        }
        if (group == SalesAnalysis.GroupBy.PRODUCT) {
            headers.add(m("salesReport.area"));
        }
        headers.add(m("salesReport.net"));
        if (seeCost) {
            headers.addAll(List.of(m("salesReport.cost"), m("salesReport.margin"), m("salesReport.marginPercent")));
        }
        if (group == SalesAnalysis.GroupBy.INVOICE) {
            headers.add(m("salesReport.toHandOver"));
        }
        String covers = m("salesReport.exportSubtitle", m("salesReport.by." + group), p.from().format(DAY), p.to().format(DAY));
        Excel.Builder sheet = Excel.sheet(m("salesReport.title"), m("salesReport.title"), covers, headers.toArray(String[]::new));
        for (SalesAnalysis.Row r : report.rows()) {
            sheet.row(cells(r, group, seeCost, false));
        }
        sheet.bold(cells(report.total(), group, seeCost, true));
        activityLogService.record(MODULE, "EXPORT_SALES_REPORT", "Exported the sales by " + group + " from " + p.from() + " to " + p.to()
                + " to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "sales-" + group.name().toLowerCase() + "-" + p.from() + "-" + p.to(), sheet.build());
    }

    private Object[] cells(SalesAnalysis.Row r, SalesAnalysis.GroupBy group, boolean seeCost, boolean total) {
        List<Object> cells = new ArrayList<>();
        if (group == SalesAnalysis.GroupBy.INVOICE) {
            if (total) {
                cells.addAll(java.util.Arrays.asList(m("cutting.total"), null, null, null));
            } else {
                SalesAnalysis.Invoice inv = r.invoice();
                cells.addAll(java.util.Arrays.asList(inv.number(), inv.date(), inv.buyer() != null ? inv.buyer() : inv.customer(), inv.salespersonName()));
            }
        } else {
            cells.add(total ? m("cutting.total") : label(r, group));
            cells.add(r.invoices());
        }
        if (group == SalesAnalysis.GroupBy.PRODUCT) {
            cells.add(r.areaM2() == null || r.areaM2().signum() == 0 ? null : r.areaM2());
        }
        cells.add(r.net());
        if (seeCost) {
            cells.add(r.cost());
            cells.add(r.getMargin());
            cells.add(r.getMarginPercent());
        }
        if (group == SalesAnalysis.GroupBy.INVOICE) {
            cells.add(!total && r.invoice().pending() ? m("salesReport.pendingShort") : null);
        }
        return cells.toArray();
    }

    /** What a row groups, as text: a day or month formatted, otherwise its label. */
    private String label(SalesAnalysis.Row r, SalesAnalysis.GroupBy group) {
        return switch (group) {
            case DAY -> r.date().format(DAY);
            case MONTH -> r.date().format(DateTimeFormatter.ofPattern("MM/yyyy"));
            default -> r.label();
        };
    }

    private static String blank(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private String m(String key, Object... args) {
        return messages.get(key, args);
    }
}
