package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.InventoryKpiReview;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : InventoryReportController.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The inventory report by week, month, quarter or year (Inventory Policy 18; M.04-M.06), and the inventory
 *               KPIs month by month against their targets (Policy 19, Annex D; M.07, K.17, L.14, T.12) with the formal
 *               review when accuracy stays below target. Both exported to Excel or PDF. The report: PAGE_INVENTORY_REPORT;
 *               the KPIs: PAGE_INVENTORY_KPIS, the review PERM_REVIEW_INVENTORY_KPI. Values need PERM_VIEW_STOCK_COST.
 * </pre>
 */
@Controller
public class InventoryReportController {

    static final String MODULE = "Reports";
    private static final String REPORT = "hasAuthority('PAGE_INVENTORY_REPORT')";
    private static final String KPIS = "hasAuthority('PAGE_INVENTORY_KPIS')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");
    private static final int MONTHS = 12;

    private final InventoryReportService reportService;
    private final InventoryKpiService kpiService;
    private final InventorySheets inventorySheets;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public InventoryReportController(InventoryReportService reportService, InventoryKpiService kpiService, InventorySheets inventorySheets,
                                     ReportFiles reportFiles, ActivityLogService activityLogService, Messages messages) {
        this.reportService = reportService;
        this.kpiService = kpiService;
        this.inventorySheets = inventorySheets;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    // ---------------------------------------------------------------- the inventory report (IMP 18)

    @GetMapping("/reports/inventory")
    @PreAuthorize(REPORT)
    public String report(@RequestParam(defaultValue = "MONTH") InventoryPeriods.Kind kind,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                         @RequestParam(defaultValue = "0") int page, Model model) {
        LocalDate today = reportService.today();
        LocalDate d = day == null || day.isAfter(today) ? today : day;
        InventoryReportService.Report report = reportService.report(kind, d);
        model.addAttribute("report", report);
        model.addAttribute("kind", kind);
        model.addAttribute("kinds", InventoryPeriods.Kind.values());
        model.addAttribute("day", d);
        model.addAttribute("today", today);
        model.addAttribute("countPage", Paging.of(report.counts(), Paging.page(page)));
        model.addAttribute("kpiCodes", InventoryKpis.Code.values());
        model.addAttribute("periodLabel", periodLabel(report.period()));
        model.addAttribute("query", QueryString.of("kind", kind.name(), "day", d.toString()));
        return "reports/inventory";
    }

    @GetMapping("/reports/inventory/export")
    @PreAuthorize(REPORT)
    public ResponseEntity<byte[]> reportExport(@RequestParam(defaultValue = "MONTH") InventoryPeriods.Kind kind,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                                               @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        boolean seeCost = AppUserPrincipal.currentHas("PERM_VIEW_STOCK_COST");
        LocalDate today = reportService.today();
        InventoryReportService.Report r = reportService.report(kind, day == null || day.isAfter(today) ? today : day);
        String sub = periodLabel(r.period());
        String title = messages.get("inventoryReport.kind." + kind);

        Excel.Builder summary = Excel.sheet(title, title, sub, messages.get("stockRecon.check"), messages.get("stockRecon.figure"));
        if (seeCost) {
            summary.row(messages.get("inventoryReport.openingValue"), r.openingValue());
            summary.row(messages.get("inventoryReport.closingValue"), r.closingValue());
            summary.bold(messages.get("inventoryReport.change"), r.getChange());
        }
        summary.row(messages.get("inventoryReport.unitsHeld"), r.sites().total().units());
        summary.row(messages.get("summary.area"), r.sites().total().areaM2());
        summary.row(messages.get("inventoryReport.unitsIn"), r.sites().total().inUnits());
        summary.row(messages.get("inventoryReport.unitsOut"), r.sites().total().outUnits());
        summary.row(messages.get("inventoryReport.damage"), r.damage().total().units());
        summary.row(messages.get("inventoryReport.lostUnits"), r.lostUnits());
        if (seeCost) {
            summary.row(messages.get("inventoryReport.damageValue"), r.damage().total().value());
            summary.row(messages.get("inventoryReport.lostValue"), r.lostValue());
        }
        summary.row(messages.get("inventoryReport.countsClosed"), r.counts().size());
        summary.row(messages.get("inventoryReport.accuracy"), r.accuracy());
        summary.row(messages.get("inventoryReport.variancesOpened"), r.variances().opened());
        summary.row(messages.get("inventoryReport.variancesResolved"), r.variances().resolved());
        summary.row(messages.get("inventoryReport.variancesEscalated"), r.variances().escalated());
        summary.row(messages.get("inventoryReport.slow", r.slow().days()), r.slow().total().oldPieces());
        summary.row(messages.get("inventoryReport.obsolete", r.obsoleteDays()), r.obsolete().oldPieces());
        summary.row(messages.get("inventoryReport.daysSigned"), r.reconciliation().daysSigned() + " / " + r.reconciliation().daysDue());
        summary.row(messages.get("inventoryReport.daysCountersigned"), r.reconciliation().daysCountersigned() + " / " + r.reconciliation().daysDue());

        Excel.Builder sites = inventorySheets.sites(r.sites(), messages.get("inventoryReport.bySite"), sub, seeCost);
        Excel.Builder types = Excel.sheet(messages.get("stockReview.byType"), messages.get("stockReview.byType"), sub,
                messages.get("stock.movementType"), messages.get("stockRecon.count"), messages.get("summary.area"), messages.get("stockRecon.cost"));
        r.byType().forEach(t -> types.row(messages.get("stock.movement." + t.type()), t.count(), t.areaM2(), seeCost ? t.cost() : null));
        Excel.Builder damage = Excel.sheet(messages.get("inventoryReport.damageLoss"), messages.get("inventoryReport.damageLoss"), sub,
                messages.get("damage.register.source"), messages.get("damage.register.rows"), messages.get("damage.register.units"),
                messages.get("summary.area"), messages.get("damage.register.value"));
        r.damage().totals().forEach(t -> damage.row(messages.get("damage.register.source." + t.source()), t.rows(), t.units(), t.areaM2(),
                seeCost ? t.value() : null));
        damage.bold(messages.get("damage.register.total"), r.damage().total().rows(), r.damage().total().units(), r.damage().total().areaM2(),
                seeCost ? r.damage().total().value() : null);
        damage.row(messages.get("inventoryReport.lostUnits"), null, r.lostUnits(), null, seeCost ? r.lostValue() : null);
        Excel.Builder counts = Excel.sheet(messages.get("inventoryReport.counts"), messages.get("inventoryReport.counts"), sub,
                messages.get("count.number"), messages.get("count.type"), messages.get("count.place"), messages.get("inventoryReport.closedAt"),
                messages.get("count.kpi.expected"), messages.get("inventoryReport.matched"), messages.get("count.kpi.missing"), messages.get("count.kpi.misplaced"),
                messages.get("count.kpi.extra"), messages.get("inventoryReport.accuracy"), messages.get("inventoryReport.auditors"));
        for (InventoryReportService.CountRow c : r.counts()) {
            counts.row(c.number(), messages.get("count.type." + c.type()), c.place(), c.closedAt().toLocalDate(), c.expected(), c.matched(),
                    c.missing(), c.misplaced(), c.extra(), c.accuracy(), String.join(", ", c.auditors()));
        }
        counts.bold(messages.get("cutting.total"), null, null, null, r.countedExpected(), r.countedMatched(), null, null, null, r.accuracy(), null);
        Excel.Builder kpis = inventorySheets.kpis(messages.get("inventoryKpi.title"), sub, List.of(r.previousKpis(), r.kpis()),
                List.of(periodLabel(InventoryPeriods.previous(r.period())), sub));
        Excel.Builder recon = Excel.sheet(messages.get("inventoryReport.reconciliation"), messages.get("inventoryReport.reconciliation"), sub,
                messages.get("inventoryReport.month"), messages.get("inventoryReport.monthSigned"), messages.get("inventoryReport.aboveTolerance"));
        for (InventoryReportService.MonthStatus m : r.reconciliation().months()) {
            recon.row(m.month().format(MONTH), m.signedAt() == null ? (m.ended() ? messages.get("inventoryReport.notSigned") : messages.get("inventoryReport.running"))
                    : m.signedBy() + " - " + m.signedAt().format(DAY), m.aboveTolerance() ? messages.get("common.yes") : "");
        }
        activityLogService.record(MODULE, "EXPORT_INVENTORY_REPORT", "Exported the " + kind.name().toLowerCase() + "ly inventory report of "
                + r.period().from() + " to " + r.period().to() + " to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "inventory-report-" + kind.name().toLowerCase() + "-" + r.period().from(), summary.build(), sites.build(),
                types.build(), damage.build(), counts.build(), kpis.build(), recon.build());
    }

    // ---------------------------------------------------------------- the KPIs (IMP 19)

    @GetMapping("/reports/kpis")
    @PreAuthorize(KPIS)
    public String kpis(@RequestParam(required = false) String month, @RequestParam(defaultValue = "0") int page, Model model) {
        YearMonth last = month(month);
        List<InventoryKpiService.Measure> months = kpiService.months(last, MONTHS);
        model.addAttribute("months", months);
        // Newest first on the page, so this month is always in view; the export runs oldest first
        List<InventoryKpiService.Measure> newest = new ArrayList<>(months);
        java.util.Collections.reverse(newest);
        model.addAttribute("monthsNewest", newest);
        InventoryKpiService.Measure latest = months.get(months.size() - 1);
        model.addAttribute("latest", latest);
        model.addAttribute("featured", List.of(latest.get(InventoryKpis.Code.ACCURACY), latest.get(InventoryKpis.Code.STOCK_LOSS),
                latest.get(InventoryKpis.Code.DAMAGED), latest.get(InventoryKpis.Code.DELIVERY_ACCURACY)));
        model.addAttribute("codes", InventoryKpis.Code.values());
        model.addAttribute("targets", kpiService.targets());
        model.addAttribute("breach", kpiService.breach().orElse(null));
        model.addAttribute("reviews", kpiService.reviews(Paging.page(page), Paging.SIZE));
        model.addAttribute("monthValue", last.toString());
        model.addAttribute("maxMonth", YearMonth.from(kpiService.today()).toString());
        model.addAttribute("query", QueryString.of("month", last.toString()));
        return "reports/kpis";
    }

    @PostMapping("/reports/kpis/review")
    @PreAuthorize(KPIS + " and hasAuthority('PERM_REVIEW_INVENTORY_KPI')")
    public String review(@RequestParam String month, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        YearMonth m = month(month);
        try {
            InventoryKpiReview r = kpiService.review(m, note);
            activityLogService.record(MODULE, "REVIEW_INVENTORY_KPI", "Recorded the formal review of inventory accuracy for " + m + " ("
                    + r.getMonthsBelow() + " month(s) below " + r.getTarget().toPlainString() + "%): " + r.getNote(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("kpi.review.recorded", m.format(MONTH)));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "REVIEW_INVENTORY_KPI", "Failed to record the formal review of inventory accuracy for " + m + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/reports/kpis";
    }

    @GetMapping("/reports/kpis/export")
    @PreAuthorize(KPIS)
    public ResponseEntity<byte[]> kpisExport(@RequestParam(required = false) String month, @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        YearMonth last = month(month);
        List<InventoryKpiService.Measure> months = kpiService.months(last, MONTHS);
        List<String> labels = months.stream().map(m -> m.getMonth().format(MONTH)).toList();
        String sub = messages.get("inventoryKpi.exportSubtitle", labels.get(0), labels.get(labels.size() - 1));
        Excel.Builder sheet = inventorySheets.kpis(messages.get("inventoryKpi.title"), sub, months, labels);
        activityLogService.record(MODULE, "EXPORT_INVENTORY_KPIS", "Exported the inventory KPIs up to " + last + " to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "inventory-kpis-" + last, sheet.build());
    }

    // ---------------------------------------------------------------- helpers

    String periodLabel(InventoryPeriods.Period p) {
        return switch (p.kind()) {
            case MONTH -> messages.get("inventoryReport.periodMonth", p.from().format(MONTH));
            case YEAR -> messages.get("inventoryReport.periodYear", String.valueOf(p.from().getYear()));
            default -> messages.get("fin.period", p.from().format(DAY), p.to().format(DAY));
        };
    }

    /** The month asked (yyyy-MM), else this one; never after this month. */
    private YearMonth month(String month) {
        YearMonth now = YearMonth.from(kpiService.today());
        YearMonth m;
        try {
            m = month == null || month.isBlank() ? now : YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            m = now;
        }
        return m.isAfter(now) ? now : m;
    }
}
