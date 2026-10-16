package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.SalesTargetScope;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.SalesTargetService;
import com.ntaganira.heritier.iWarehouse.service.WeeklyReportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : SalesTargetController.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Sales targets (SDP 15, 16; Annex D): the grid of a year and scope (PERM_MANAGE_SALES_TARGETS to change it,
 *               PAGE_SALES_TARGETS), the sales against target of a week or a month (PAGE_SALES_TARGET_REPORT) and the
 *               weekly report (PAGE_WEEKLY_REPORT), each exported to Excel or PDF.
 * </pre>
 */
@Controller
public class SalesTargetController {

    private static final String MODULE = "Sales";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SalesTargetService targets;
    private final WeeklyReportService weekly;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public SalesTargetController(SalesTargetService targets, WeeklyReportService weekly, ReportFiles reportFiles, ActivityLogService activityLogService,
                                 Messages messages) {
        this.targets = targets;
        this.weekly = weekly;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping("/sales-targets")
    @PreAuthorize("hasAuthority('PAGE_SALES_TARGETS') and hasAuthority('PERM_VIEW_SALES_TARGETS')")
    public String grid(@RequestParam(required = false) Integer year, @RequestParam(defaultValue = "TERRITORY") SalesTargetScope scope,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        int y = year == null ? targets.today().getYear() : year;
        List<SalesTargetService.GridRow> rows = targets.grid(y, scope);
        model.addAttribute("year", y);
        model.addAttribute("scope", scope);
        model.addAttribute("scopes", SalesTargetScope.values());
        model.addAttribute("rows", rows);
        model.addAttribute("salespeople", scope == SalesTargetScope.SALESPERSON ? targets.salespeople(rows.stream().map(SalesTargetService.GridRow::key).toList())
                : List.of());
        BigDecimal[] totals = new BigDecimal[12];
        Arrays.fill(totals, BigDecimal.ZERO);
        rows.forEach(r -> { for (int m = 0; m < 12; m++) { totals[m] = totals[m].add(r.months()[m]); } });
        model.addAttribute("monthTotals", totals);
        model.addAttribute("monthStarts", com.ntaganira.heritier.iWarehouse.service.Budgets.monthStarts(y));
        return "sales-targets/grid";
    }

    /** Saves the grid: fields target[<key>][<month>]. */
    @PostMapping("/sales-targets")
    @PreAuthorize("hasAuthority('PAGE_SALES_TARGETS') and hasAuthority('PERM_MANAGE_SALES_TARGETS')")
    public String save(@RequestParam int year, @RequestParam SalesTargetScope scope, @RequestParam(required = false) String addKey,
                       HttpServletRequest request, RedirectAttributes redirect) {
        Map<String, List<BigDecimal>> rows = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (!name.startsWith("target[") || values.length == 0) {
                return;
            }
            int close = name.indexOf("][");
            String key = name.substring(7, close);
            int month = Integer.parseInt(name.substring(close + 2, name.length() - 1));
            BigDecimal amount;
            try {
                amount = StringUtils.hasText(values[0]) ? new BigDecimal(values[0].trim()) : BigDecimal.ZERO;
            } catch (NumberFormatException e) {
                amount = BigDecimal.ZERO;
            }
            List<BigDecimal> months = rows.computeIfAbsent(key, k -> new ArrayList<>(Collections.nCopies(12, BigDecimal.ZERO)));
            if (month >= 1 && month <= 12) {
                months.set(month - 1, amount);
            }
        });
        if (StringUtils.hasText(addKey)) {
            rows.putIfAbsent(addKey.trim(), new ArrayList<>(Collections.nCopies(12, BigDecimal.ZERO)));
        }
        try {
            int changed = targets.save(year, scope, rows);
            activityLogService.record(MODULE, "UPDATE_SALES_TARGETS", "Saved the " + scope + " sales targets of " + year + " (" + changed + " month(s) changed)",
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("salesTarget.saved", String.valueOf(year)));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_SALES_TARGETS", "Failed to save the sales targets of " + year + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/sales-targets?year=" + year + "&scope=" + scope;
    }

    @GetMapping("/reports/sales-targets")
    @PreAuthorize("hasAuthority('PAGE_SALES_TARGET_REPORT')")
    public String report(@RequestParam(defaultValue = "month") String period, @RequestParam(required = false) Integer year,
                         @RequestParam(required = false) Integer month, @RequestParam(required = false) YearMonth ym,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                         @RequestParam(defaultValue = "0") int page, Model model) {
        SalesTargetService.Report r = ym != null && !"week".equals(period) ? report(period, ym.getYear(), ym.getMonthValue(), day)
                : report(period, year, month, day);
        model.addAttribute("report", r);
        model.addAttribute("period", r.week() ? "week" : "month");
        model.addAttribute("year", r.from().getYear());
        model.addAttribute("month", r.from().getMonthValue());
        model.addAttribute("day", r.from());
        model.addAttribute("today", targets.today());
        model.addAttribute("territoryPage", Paging.of(r.territories(), Paging.page(page)));
        model.addAttribute("query", query(r));
        return "reports/sales-targets";
    }

    @GetMapping("/reports/sales-targets/export")
    @PreAuthorize("hasAuthority('PAGE_SALES_TARGET_REPORT')")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "month") String period, @RequestParam(required = false) Integer year,
                                         @RequestParam(required = false) Integer month,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                                         @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        SalesTargetService.Report r = report(period, year, month, day);
        String sub = messages.get("fin.period", r.from().format(DAY), r.to().format(DAY));
        List<Excel.Builder> sheets = new ArrayList<>();
        sheets.add(targetSheet(messages.get("salesTarget.reportTitle"), sub, List.of(r.company()), true));
        sheets.add(targetSheet(messages.get("salesTarget.byTerritory"), sub, r.territories(), false));
        sheets.add(targetSheet(messages.get("salesTarget.bySalesperson"), sub, r.salespeople(), false));
        sheets.add(targetSheet(messages.get("salesTarget.byMarket"), sub, r.markets().stream()
                .map(t -> new SalesTargetService.TargetRow(t.key(), messages.get("order.market." + t.key()), t.actual(), t.target())).toList(), false));
        activityLogService.record(MODULE, "EXPORT_SALES_TARGETS", "Exported the sales against target from " + r.from() + " to " + r.to() + " to " + format.label(),
                ActivityStatus.SUCCESS);
        return reportFiles.download(format, "sales-targets-" + r.from() + "-" + r.to(), sheets.stream().map(Excel.Builder::build).toArray(Excel.Sheet[]::new));
    }

    @GetMapping("/reports/weekly")
    @PreAuthorize("hasAuthority('PAGE_WEEKLY_REPORT')")
    public String weekly(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day, @RequestParam(defaultValue = "0") int page,
                         Model model) {
        WeeklyReportService.Report r = weekly.report(day == null ? weekly.today() : day);
        model.addAttribute("report", r);
        model.addAttribute("day", r.from());
        model.addAttribute("today", weekly.today());
        model.addAttribute("territoryPage", Paging.of(r.sales().territories(), Paging.page(page)));
        model.addAttribute("buckets", com.ntaganira.heritier.iWarehouse.service.Ageing.Bucket.values());
        model.addAttribute("query", QueryString.of("day", r.from().toString()));
        return "reports/weekly";
    }

    @GetMapping("/reports/weekly/export")
    @PreAuthorize("hasAuthority('PAGE_WEEKLY_REPORT')")
    public ResponseEntity<byte[]> weeklyExport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                                               @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        WeeklyReportService.Report r = weekly.report(day == null ? weekly.today() : day);
        String sub = messages.get("fin.period", r.from().format(DAY), r.to().format(DAY));
        Excel.Builder summary = Excel.sheet(messages.get("weekly.title"), messages.get("weekly.title"), sub, messages.get("stockRecon.check"),
                messages.get("stockRecon.figure"));
        summary.row(messages.get("weekly.sales"), r.sales().company().actual());
        summary.row(messages.get("weekly.target"), r.sales().company().target());
        summary.row(messages.get("weekly.achieved"), r.sales().company().getAchieved());
        summary.row(messages.get("weekly.variances"), r.variances());
        summary.row(messages.get("weekly.missingValue"), r.missingValue());
        summary.row(messages.get("weekly.foundValue"), r.foundValue());
        summary.row(messages.get("weekly.voids"), r.voids());
        summary.row(messages.get("weekly.voidAmount"), r.voidAmount());
        summary.row(messages.get("weekly.creditNotes"), r.creditNotes());
        summary.row(messages.get("weekly.creditAmount"), r.creditAmount());
        for (com.ntaganira.heritier.iWarehouse.service.Ageing.Bucket b : com.ntaganira.heritier.iWarehouse.service.Ageing.Bucket.values()) {
            summary.row(messages.get("weekly.receivables") + " · " + messages.get("ageing." + b), r.receivables().getOrDefault(b, BigDecimal.ZERO));
        }
        summary.row(messages.get("weekly.receivablesTotal"), r.receivablesTotal());
        Excel.Builder territories = targetSheet(messages.get("salesTarget.byTerritory"), sub, r.sales().territories(), false);
        activityLogService.record(MODULE, "EXPORT_WEEKLY_REPORT", "Exported the weekly report of " + r.from() + " to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "weekly-" + r.from(), summary.build(), territories.build());
    }

    // ---------------------------------------------------------------- helpers

    private SalesTargetService.Report report(String period, Integer year, Integer month, LocalDate day) {
        LocalDate today = targets.today();
        if ("week".equals(period)) {
            LocalDate[] w = targets.week(day == null ? today : day);
            return targets.report(w[0], w[1], true);
        }
        YearMonth ym = year == null ? YearMonth.from(today) : YearMonth.of(year, month == null ? 1 : Math.max(1, Math.min(12, month)));
        return targets.report(ym.atDay(1), ym.atEndOfMonth(), false);
    }

    private String query(SalesTargetService.Report r) {
        return r.week() ? QueryString.of("period", "week", "day", r.from().toString())
                : QueryString.of("period", "month", "year", String.valueOf(r.from().getYear()), "month", String.valueOf(r.from().getMonthValue()));
    }

    private Excel.Builder targetSheet(String title, String sub, List<SalesTargetService.TargetRow> rows, boolean company) {
        Excel.Builder sheet = Excel.sheet(title, title, sub, messages.get("salesTarget.key"), messages.get("salesTarget.actual"), messages.get("salesTarget.target"),
                messages.get("salesTarget.gap"), messages.get("salesTarget.achieved"));
        for (SalesTargetService.TargetRow t : rows) {
            sheet.row(company ? messages.get("salesTarget.company") : SalesTargetService.NO_TERRITORY.equals(t.key()) ? messages.get("order.noTerritory") : t.label(),
                    t.actual(), t.target(), t.getGap(), t.getAchieved());
        }
        return sheet;
    }
}
