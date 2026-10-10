package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.AccountingPeriod;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.PeriodService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : PeriodController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Monthly period close (ACC-10): what the books are closed through, the month to close next with what it
 *               waits for, closing it, the closed months, and a month with its History and, the latest one, reopening.
 *               PAGE_PERIODS + PERM_VIEW_ACCOUNTING; closing PERM_CLOSE_PERIOD, reopening PERM_REOPEN_PERIOD.
 * </pre>
 */
@Controller
@RequestMapping("/accounting/periods")
public class PeriodController {

    private static final int REASON_MAX = 255;
    private static final String BASE = "/accounting/periods";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PeriodService periodService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public PeriodController(PeriodService periodService, DataChangeService dataChangeService, ActivityLogService activityLogService,
                            Messages messages) {
        this.periodService = periodService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_PERIODS') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        Optional<YearMonth> next = periodService.next();
        model.addAttribute("closedThrough", periodService.closedThrough());
        model.addAttribute("checklist", next.map(periodService::checklist).orElse(null));
        model.addAttribute("following", periodService.following().orElse(null));
        model.addAttribute("periods", periodService.findPage(Paging.page(page), Paging.SIZE));
        return "periods/list";
    }

    @PostMapping("/close")
    @PreAuthorize("hasAuthority('PAGE_PERIODS') and hasAuthority('PERM_CLOSE_PERIOD')")
    public String close(@RequestParam(required = false) String month, RedirectAttributes redirect) {
        YearMonth chosen = parse(month);
        try {
            AccountingPeriod period = periodService.close(chosen);
            activityLogService.record(AccountingController.MODULE, "CLOSE_PERIOD", "Closed the month ending " + period.getPeriodEnd(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("period.closedMsg", period.getPeriodEnd().format(DAY)));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(AccountingController.MODULE, "CLOSE_PERIOD", "Failed to close the month " + month + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:" + BASE;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_PERIODS') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        AccountingPeriod period = periodService.findById(id);
        model.addAttribute("period", period);
        model.addAttribute("canReopen", periodService.canReopen(period));
        model.addAttribute("history", dataChangeService.history("AccountingPeriod", id.toString(), Paging.page(page), Paging.SIZE));
        return "periods/view";
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAuthority('PAGE_PERIODS') and hasAuthority('PERM_REOPEN_PERIOD')")
    public String reopen(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:" + BASE + "/" + id;
        }
        try {
            AccountingPeriod period = AuditContext.withReason(reason.trim(), () -> periodService.reopen(id, reason));
            activityLogService.record(AccountingController.MODULE, "REOPEN_PERIOD", "Reopened the month ending " + period.getPeriodEnd()
                    + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("period.reopenedMsg", period.getPeriodEnd().format(DAY)));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(AccountingController.MODULE, "REOPEN_PERIOD", "Failed to reopen period " + id + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:" + BASE + "/" + id;
    }

    private static YearMonth parse(String month) {
        if (!StringUtils.hasText(month)) {
            return null;
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
