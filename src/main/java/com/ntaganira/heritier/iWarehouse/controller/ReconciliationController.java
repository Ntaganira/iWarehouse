package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.BankReconciliation;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.ReconciliationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : ReconciliationController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Bank and mobile-money reconciliation (ACC-12): the reconciliations, reconciling a statement (the account,
 *               its date and closing balance, then the lines it shows, ticked), a reconciliation with the lines it
 *               cleared and what was outstanding, and cancelling the latest one of an account.
 *               PAGE_RECONCILIATIONS + PERM_VIEW_ACCOUNTING; reconciling and cancelling PERM_RECONCILE_ACCOUNT.
 * </pre>
 */
@Controller
@RequestMapping("/accounting/reconciliations")
public class ReconciliationController {

    private static final int REASON_MAX = 255;
    private static final String BASE = "/accounting/reconciliations";

    private final ReconciliationService reconciliationService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public ReconciliationController(ReconciliationService reconciliationService, DataChangeService dataChangeService,
                                    ActivityLogService activityLogService, Messages messages, NumberFormats num) {
        this.reconciliationService = reconciliationService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_RECONCILIATIONS') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("reconciliations", reconciliationService.findPage(Paging.page(page), Paging.SIZE));
        return "reconciliations/list";
    }

    /** Reconciling a statement: the account, date and closing balance first, then the lines not cleared yet. */
    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_RECONCILIATIONS') and hasAuthority('PERM_RECONCILE_ACCOUNT')")
    public String form(@RequestParam(required = false) UUID account,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                       @RequestParam(required = false) BigDecimal balance, Model model) {
        List<com.ntaganira.heritier.iWarehouse.entity.Account> accounts = reconciliationService.accounts();
        UUID chosen = account != null ? account : accounts.isEmpty() ? null : accounts.get(0).getId();
        model.addAttribute("accounts", accounts);
        model.addAttribute("account", chosen);
        model.addAttribute("date", date == null ? reconciliationService.today() : date);
        model.addAttribute("balance", balance);
        model.addAttribute("today", reconciliationService.today());
        model.addAttribute("previous", chosen == null ? null : reconciliationService.latest(chosen).orElse(null));
        if (chosen != null && date != null && balance != null) {
            try {
                model.addAttribute("draft", reconciliationService.draft(chosen, date, balance));
            } catch (BusinessException e) {
                model.addAttribute("errorField", e.getField());
                model.addAttribute("errorText", messages.get(e.getMessageKey(), e.getArgs()));
            }
        }
        return "reconciliations/form";
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_RECONCILIATIONS') and hasAuthority('PERM_RECONCILE_ACCOUNT')")
    public String reconcile(@RequestParam UUID account, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                            @RequestParam BigDecimal balance, @RequestParam(required = false) List<UUID> lineIds,
                            @RequestParam(required = false) String notes, RedirectAttributes redirect) {
        try {
            BankReconciliation rec = reconciliationService.reconcile(account, date, balance, lineIds, notes);
            activityLogService.record(AccountingController.MODULE, "CREATE_RECONCILIATION", "Reconciled " + rec.getAccount().getCode() + " "
                    + rec.getAccount().getName() + " with its statement of " + rec.getStatementDate() + " (" + rec.getNumber() + "): "
                    + num.money(rec.getStatementBalance()) + " RWF, " + (lineIds == null ? 0 : lineIds.size()) + " line(s) cleared", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("reconciliation.created", rec.getNumber()));
            return "redirect:" + BASE + "/" + rec.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(AccountingController.MODULE, "CREATE_RECONCILIATION", "Failed to reconcile account " + account
                    + " with its statement of " + date + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
            redirect.addFlashAttribute("ticked", lineIds == null ? List.of() : lineIds);
            redirect.addFlashAttribute("notes", notes);
            return "redirect:" + BASE + "/new?account=" + account + "&date=" + date + "&balance=" + balance.toPlainString();
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_RECONCILIATIONS') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int hpage,
                       Model model) {
        BankReconciliation rec = reconciliationService.findDetailed(id);
        model.addAttribute("rec", rec);
        model.addAttribute("lines", Paging.of(reconciliationService.lines(id), Paging.page(page)));
        model.addAttribute("canCancel", reconciliationService.canCancel(rec));
        model.addAttribute("history", dataChangeService.history("BankReconciliation", id.toString(), Paging.page(hpage), Paging.SIZE));
        model.addAttribute("linesQuery", QueryString.of("hpage", hpage > 0 ? String.valueOf(hpage) : null));
        model.addAttribute("historyQuery", QueryString.of("page", page > 0 ? String.valueOf(page) : null));
        return "reconciliations/view";
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_RECONCILIATIONS') and hasAuthority('PERM_RECONCILE_ACCOUNT')")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:" + BASE + "/" + id;
        }
        try {
            BankReconciliation rec = AuditContext.withReason(reason.trim(), () -> reconciliationService.cancel(id, reason));
            activityLogService.record(AccountingController.MODULE, "CANCEL_RECONCILIATION", "Cancelled reconciliation " + rec.getNumber()
                    + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("reconciliation.cancelledMsg", rec.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(AccountingController.MODULE, "CANCEL_RECONCILIATION", "Failed to cancel reconciliation " + id + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:" + BASE + "/" + id;
    }
}
