package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.FxRevaluation;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.FxRevaluationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : FxRevaluationController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Month-end revaluation of open foreign balances (ACC-08, unrealised FX): the months revalued, what
 *               revaluing an ended month would post (open balances at its last day's rate), posting it, and a
 *               revaluation with its lines, journal, reversal and History.
 *               PAGE_FX_REVALUATIONS + PERM_VIEW_ACCOUNTING; posting PERM_REVALUE_FX.
 * </pre>
 */
@Controller
@RequestMapping("/accounting/fx-revaluations")
public class FxRevaluationController {

    private final FxRevaluationService revaluationService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public FxRevaluationController(FxRevaluationService revaluationService, DataChangeService dataChangeService,
                                   ActivityLogService activityLogService, Messages messages, NumberFormats num) {
        this.revaluationService = revaluationService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_FX_REVALUATIONS') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("revaluations", revaluationService.findPage(Paging.page(page), Paging.SIZE));
        model.addAttribute("months", revaluationService.months());
        return "fx-revaluations/list";
    }

    /** What revaluing a month would post: the latest month not revalued unless one is chosen. */
    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_FX_REVALUATIONS') and hasAuthority('PERM_REVALUE_FX')")
    public String form(@RequestParam(required = false) String month, Model model, RedirectAttributes redirect) {
        List<YearMonth> months = revaluationService.months();
        model.addAttribute("months", months);
        model.addAttribute("nextMonth", YearMonth.from(revaluationService.today()).atEndOfMonth().plusDays(1));
        YearMonth chosen = parse(month);
        if (chosen == null || !months.contains(chosen)) {
            chosen = months.isEmpty() ? null : months.get(0);
        }
        model.addAttribute("month", chosen);
        if (chosen != null) {
            try {
                FxRevaluationService.Preview preview = revaluationService.preview(chosen);
                model.addAttribute("preview", preview);
                model.addAttribute("missingRates", preview.missingRates().stream()
                        .map(e -> messages.get(e.getMessageKey(), e.getArgs())).toList());
            } catch (BusinessException e) {
                redirect.addFlashAttribute("flashError", messages.get(e.getMessageKey(), e.getArgs()));
                return "redirect:/accounting/fx-revaluations";
            }
        }
        return "fx-revaluations/form";
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_FX_REVALUATIONS') and hasAuthority('PERM_REVALUE_FX')")
    public String post(@RequestParam(required = false) String month, RedirectAttributes redirect) {
        YearMonth chosen = parse(month);
        try {
            FxRevaluation revaluation = revaluationService.post(chosen);
            String result = result(revaluation);
            activityLogService.record(AccountingController.MODULE, "POST_FX_REVALUATION", "Revalued the foreign balances of "
                    + revaluation.getPeriodEnd() + " (" + revaluation.getNumber() + "): " + result, ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("fxRevaluation.posted", revaluation.getNumber(), result));
            return "redirect:/accounting/fx-revaluations/" + revaluation.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(AccountingController.MODULE, "POST_FX_REVALUATION", "Failed to revalue the foreign balances of "
                    + month + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
            return chosen == null ? "redirect:/accounting/fx-revaluations/new" : "redirect:/accounting/fx-revaluations/new?month=" + chosen;
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_FX_REVALUATIONS') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("revaluation", revaluationService.findById(id));
        model.addAttribute("lines", revaluationService.lines(id));
        model.addAttribute("journals", revaluationService.journals(id));
        model.addAttribute("history", dataChangeService.history("FxRevaluation", id.toString(), Paging.page(page), Paging.SIZE));
        return "fx-revaluations/view";
    }

    /** "Gain of 1,200", "Loss of 4,320.50" (RWF) or "no gain or loss overall". */
    private String result(FxRevaluation revaluation) {
        if (revaluation.getGainLoss().signum() == 0) {
            return messages.get("fxRevaluation.even");
        }
        return revaluation.getGainLoss().signum() > 0
                ? messages.get("fxRevaluation.gain", num.money(revaluation.getGainLoss()))
                : messages.get("fxRevaluation.loss", num.money(revaluation.getGainLoss().negate()));
    }

    private static YearMonth parse(String month) {
        if (month == null || month.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
