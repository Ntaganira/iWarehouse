package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : ManagementPackController.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The monthly management accounts pack (BP 13.1, Table 6; T.10): its key figures on a page and the whole pack
 *               as one PDF or Excel workbook (the income statement of the month and the year to date, the balance sheet,
 *               the receivables and payables ageing, the stock position, sales by territory and by channel, collections
 *               by territory, the inventory KPIs). PAGE_MANAGEMENT_PACK.
 * </pre>
 */
@Controller
public class ManagementPackController {

    static final String MODULE = "Reports";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final ManagementPackService packService;
    private final StatementSheets statementSheets;
    private final AgeingSheets ageingSheets;
    private final InventorySheets inventorySheets;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public ManagementPackController(ManagementPackService packService, StatementSheets statementSheets, AgeingSheets ageingSheets,
                                    InventorySheets inventorySheets, ReportFiles reportFiles,
                                    ActivityLogService activityLogService, Messages messages) {
        this.packService = packService;
        this.statementSheets = statementSheets;
        this.ageingSheets = ageingSheets;
        this.inventorySheets = inventorySheets;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping("/reports/management-pack")
    @PreAuthorize("hasAuthority('PAGE_MANAGEMENT_PACK')")
    public String pack(@RequestParam(required = false) String month, Model model) {
        YearMonth m = month(month);
        model.addAttribute("pack", packService.pack(m));
        model.addAttribute("month", m);
        model.addAttribute("monthValue", m.toString());
        model.addAttribute("maxMonth", YearMonth.from(packService.today()).toString());
        model.addAttribute("codes", InventoryKpis.Code.values());
        model.addAttribute("query", QueryString.of("month", m.toString()));
        return "reports/management-pack";
    }

    @GetMapping("/reports/management-pack/export")
    @PreAuthorize("hasAuthority('PAGE_MANAGEMENT_PACK')")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String month, @RequestParam(defaultValue = "PDF") ReportFiles.Format format) {
        YearMonth m = month(month);
        ManagementPackService.Pack p = packService.pack(m);
        String label = m.format(MONTH);
        Excel.Builder contents = Excel.sheet(messages.get("pack.contents"), messages.get("pack.title", label),
                messages.get("pack.subtitle", p.today().format(DAY)), messages.get("pack.part"), messages.get("pack.basis"));
        contents.row(messages.get("pack.part.income"), messages.get("fin.period", p.from().format(DAY), p.to().format(DAY)));
        contents.row(messages.get("pack.part.incomeYtd"), messages.get("fin.period", p.from().withDayOfYear(1).format(DAY), p.to().format(DAY)));
        contents.row(messages.get("pack.part.balance"), messages.get("fin.asOf", p.to().format(DAY)));
        contents.row(messages.get("pack.part.receivables"), messages.get("fin.asOf", p.today().format(DAY)));
        contents.row(messages.get("pack.part.payables"), messages.get("fin.asOf", p.today().format(DAY)));
        contents.row(messages.get("pack.part.stock"), messages.get("fin.asOf", p.today().format(DAY)));
        contents.row(messages.get("pack.part.territory"), label);
        contents.row(messages.get("pack.part.channel"), label);
        contents.row(messages.get("pack.part.collections"), label);
        contents.row(messages.get("pack.part.kpis"), label);
        contents.bold(messages.get("pack.inventoryAccount"), p.inventoryAccount());
        contents.bold(messages.get("pack.stockValuation"), p.stock().total().value());

        Excel.Builder stock = Excel.sheet(messages.get("pack.part.stock"), messages.get("summary.title"), messages.get("fin.asOf", p.today().format(DAY)),
                messages.get("product.name"), messages.get("summary.pieces"), messages.get("summary.area"), messages.get("summary.value"));
        for (StockSummary.Row r : p.stock().rows()) {
            stock.row(r.product() == null ? "" : r.product().getCode(), r.pieces(), r.areaM2(), r.value());
        }
        stock.bold(messages.get("cutting.total"), p.stock().total().pieces(), p.stock().total().areaM2(), p.stock().total().value());
        Excel.Sheet kpis = inventorySheets.kpis(messages.get("pack.part.kpis"), label, List.of(p.kpis()), List.of(label)).build();
        activityLogService.record(MODULE, "EXPORT_MANAGEMENT_PACK", "Exported the management pack of " + m + " to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "management-pack-" + m, contents.build(),
                statementSheets.income(messages.get("pack.part.income"), p.income(), p.from(), p.to()),
                statementSheets.income(messages.get("pack.part.incomeYtd"), p.yearToDate(), p.from().withDayOfYear(1), p.to()),
                statementSheets.balance(messages.get("pack.part.balance"), p.balance(), p.to()),
                ageingSheets.receivables(messages.get("pack.part.receivables"), p.receivables(), p.today()),
                ageingSheets.payables(messages.get("pack.part.payables"), p.payables(), p.today()), stock.build(),
                salesSheet(messages.get("pack.part.territory"), p.byTerritory(), "salesReport.group.TERRITORY"),
                salesSheet(messages.get("pack.part.channel"), p.byChannel(), "salesReport.group.CHANNEL"),
                ageingSheets.collections(p.collections(), messages.get("pack.part.collections")), kpis);
    }

    /** A month's sales grouped, with their margin. */
    private Excel.Sheet salesSheet(String name, SalesReportService.Report r, String groupKey) {
        Excel.Builder sheet = Excel.sheet(name, name, messages.get("fin.period", r.from().format(DAY), r.to().format(DAY)), messages.get(groupKey),
                messages.get("salesReport.invoices"), messages.get("summary.area"), messages.get("salesReport.net"), messages.get("salesReport.cost"),
                messages.get("salesReport.margin"), messages.get("salesReport.marginPercent"));
        for (SalesAnalysis.Row row : r.rows()) {
            sheet.row(row.label() == null || row.label().isBlank() ? messages.get("reports.none") : groupLabel(groupKey, row.label()), row.invoices(),
                    row.areaM2(), row.net(), row.cost(), row.getMargin(), row.getMarginPercent());
        }
        sheet.bold(messages.get("cutting.total"), r.total().invoices(), r.total().areaM2(), r.total().net(), r.total().cost(), r.total().getMargin(),
                r.total().getMarginPercent());
        return sheet.build();
    }

    /** Channels are names of constants: shown by their text. */
    private String groupLabel(String groupKey, String label) {
        return groupKey.endsWith("CHANNEL") ? messages.get("order.channel." + label) : label;
    }

    /** The month asked (yyyy-MM), else the last ended one; never after this month. */
    private YearMonth month(String month) {
        YearMonth now = YearMonth.from(packService.today());
        YearMonth m;
        try {
            m = month == null || month.isBlank() ? packService.lastMonth() : YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            m = packService.lastMonth();
        }
        return m.isAfter(now) ? now : m;
    }
}
