package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.CashForecastItem;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.CashDirection;
import com.ntaganira.heritier.iWarehouse.enums.CashRepeat;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.CashFlow;
import com.ntaganira.heritier.iWarehouse.service.CashFlowService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : CashFlowController.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The cash-flow forecast and the weekly cash report (P.11): the rolling forecast week by week, the actual cash of a
 *               week by kind of document with the weeks before it, and the planned items (added and cancelled with
 *               PERM_MANAGE_CASH_FORECAST); exported to Excel or PDF. PAGE_CASH_FLOW.
 * </pre>
 */
@Controller
@RequestMapping("/reports/cash-flow")
public class CashFlowController {

    private static final String MODULE = "Accounting";
    private static final String AUTH = "hasAuthority('PAGE_CASH_FLOW')";
    private static final String MANAGE = AUTH + " and hasAuthority('PERM_MANAGE_CASH_FORECAST')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    static final int WEEKS_BACK = 8;

    private final CashFlowService service;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public CashFlowController(CashFlowService service, ReportFiles reportFiles, ActivityLogService activityLogService, Messages messages,
                              NumberFormats num) {
        this.service = service;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize(AUTH)
    public String report(@RequestParam(required = false) String tab, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                         @RequestParam(defaultValue = "0") int page, Model model) {
        String open = "weekly".equals(tab) || "planned".equals(tab) ? tab : "forecast";
        LocalDate today = service.today();
        LocalDate monday = CashFlowService.mondayOf(week == null || week.isAfter(today) ? today : week);
        List<CashFlowService.CashWeek> weeks = service.weeks(monday, WEEKS_BACK);
        model.addAttribute("forecast", service.forecast());
        model.addAttribute("columns", List.of(CashFlow.Kind.RECEIVABLES, CashFlow.Kind.PAYABLES, CashFlow.Kind.PAYROLL, CashFlow.Kind.TAXES));
        model.addAttribute("weeks", weeks);
        model.addAttribute("week", weeks.get(weeks.size() - 1));
        model.addAttribute("monday", monday);
        model.addAttribute("previousWeek", monday.minusWeeks(1));
        model.addAttribute("nextWeek", monday.plusWeeks(1).isAfter(today) ? null : monday.plusWeeks(1));
        model.addAttribute("items", Paging.of(service.items(), Paging.pageOf("planned", open, page)));
        model.addAttribute("directions", CashDirection.values());
        model.addAttribute("repeats", CashRepeat.values());
        model.addAttribute("today", today);
        model.addAttribute("tab", open);
        return "reports/cash-flow";
    }

    @PostMapping("/items")
    @PreAuthorize(MANAGE)
    public String add(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expectedOn,
                      @RequestParam(required = false) CashDirection direction, @RequestParam(required = false) String description,
                      @RequestParam(required = false) BigDecimal amount, @RequestParam(required = false) CashRepeat repeats,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until, RedirectAttributes redirect) {
        try {
            CashForecastItem item = service.add(new CashFlowService.ItemForm(expectedOn, direction, description, amount, repeats, until));
            activityLogService.record(MODULE, "CREATE_CASH_FORECAST_ITEM", "Planned " + item.getDirection() + " " + num.money(item.getAmount()) + " RWF on "
                    + item.getExpectedOn().format(DAY) + " (" + item.getRepeats() + "): " + item.getDescription(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("cashflow.item.added", item.getDescription()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_CASH_FORECAST_ITEM", "Failed to plan a cash-flow item: " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/reports/cash-flow?tab=planned";
    }

    @PostMapping("/items/{id}/cancel")
    @PreAuthorize(MANAGE)
    public String cancel(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            CashForecastItem item = service.cancel(id);
            activityLogService.record(MODULE, "CANCEL_CASH_FORECAST_ITEM", "Cancelled the planned cash-flow item " + item.getDescription(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("cashflow.item.cancelledDone", item.getDescription()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CANCEL_CASH_FORECAST_ITEM", "Failed to cancel a planned cash-flow item: " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/reports/cash-flow?tab=planned";
    }

    @GetMapping("/export")
    @PreAuthorize(AUTH)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week,
                                         @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        LocalDate today = service.today();
        CashFlowService.Forecast f = service.forecast();
        String period = messages.get("fin.period", f.start().format(DAY), f.getEnd().format(DAY));
        String[] heads = new String[CashFlow.Kind.values().length + 5];
        int h = 0;
        heads[h++] = messages.get("cashflow.week");
        heads[h++] = messages.get("cashflow.opening");
        for (CashFlow.Kind k : CashFlow.Kind.values()) {
            heads[h++] = messages.get("cashflow.kind." + k);
        }
        heads[h++] = messages.get("cashflow.in");
        heads[h++] = messages.get("cashflow.out");
        heads[h] = messages.get("cashflow.closing");
        Excel.Builder forecast = Excel.sheet(messages.get("cashflow.forecastTab"), messages.get("cashflow.title"), period, heads);
        for (CashFlow.Week w : f.weeks()) {
            Object[] cells = new Object[heads.length];
            int c = 0;
            cells[c++] = messages.get("cashflow.weekRange", String.valueOf(w.no()), w.from().format(DAY), w.to().format(DAY));
            cells[c++] = w.opening();
            for (CashFlow.Kind k : CashFlow.Kind.values()) {
                cells[c++] = w.get(k);
            }
            cells[c++] = w.getTotalIn();
            cells[c++] = w.getTotalOut();
            cells[c] = w.getClosing();
            forecast.row(cells);
        }
        LocalDate monday = CashFlowService.mondayOf(week == null || week.isAfter(today) ? today : week);
        List<CashFlowService.CashWeek> weeks = service.weeks(monday, WEEKS_BACK);
        Excel.Builder weekly = Excel.sheet(messages.get("cashflow.weeklyTab"), messages.get("cashflow.weeklyTitle"),
                messages.get("fin.period", weeks.get(0).from().format(DAY), monday.plusDays(6).format(DAY)),
                messages.get("cashflow.week"), messages.get("cashflow.opening"), messages.get("cashflow.in"), messages.get("cashflow.out"),
                messages.get("cashflow.closing"));
        for (CashFlowService.CashWeek w : weeks) {
            weekly.row(messages.get("fin.period", w.from().format(DAY), w.to().format(DAY)), w.opening(), w.getTotalIn(), w.getTotalOut(), w.getClosing());
        }
        CashFlowService.CashWeek last = weeks.get(weeks.size() - 1);
        Excel.Builder detail = Excel.sheet(messages.get("cashflow.detailTab"), messages.get("cashflow.weeklyTitle"),
                messages.get("fin.period", last.from().format(DAY), last.to().format(DAY)),
                messages.get("cashflow.source"), messages.get("cashflow.in"), messages.get("cashflow.out"));
        detail.row(messages.get("cashflow.opening"), last.opening(), null);
        for (JournalSource s : last.getSources()) {
            detail.row(messages.get("journal.source." + s), last.in().get(s), last.out().get(s));
        }
        detail.bold(messages.get("fin.total"), last.getTotalIn(), last.getTotalOut());
        detail.bold(messages.get("cashflow.closing"), last.getClosing(), null);
        Excel.Builder planned = Excel.sheet(messages.get("cashflow.plannedTab"), messages.get("cashflow.plannedTab"), period,
                messages.get("cashflow.item.date"), messages.get("cashflow.item.direction"), messages.get("cashflow.item.description"),
                messages.get("cashflow.item.amount"), messages.get("cashflow.item.repeats"), messages.get("cashflow.item.until"));
        for (CashForecastItem i : service.items()) {
            planned.row(i.getExpectedOn(), messages.get("cashflow.direction." + i.getDirection()), i.getDescription(), i.getAmount(),
                    messages.get("cashflow.repeat." + i.getRepeats()), i.getUntil());
        }
        activityLogService.record(MODULE, "EXPORT_CASH_FLOW", "Exported the cash-flow forecast from " + f.start().format(DAY) + " to " + format.label(),
                ActivityStatus.SUCCESS);
        return reportFiles.download(format, "cash-flow-" + f.start(), forecast.build(), weekly.build(), detail.build(), planned.build());
    }
}
