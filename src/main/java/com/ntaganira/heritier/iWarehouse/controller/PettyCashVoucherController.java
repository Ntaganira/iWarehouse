package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.PettyCashVoucherDto;
import com.ntaganira.heritier.iWarehouse.entity.PettyCashVoucher;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.AttachmentService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.PettyCashService;
import com.ntaganira.heritier.iWarehouse.service.PettyCashVoucherService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : PettyCashVoucherController.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Petty cash vouchers (CMP 07, F-13 D): the form of a draft (the custodian), the voucher with its receipt,
 *               journal and history, and its steps: submit, approve (PERM_APPROVE_PETTY_CASH), reject, cancel, reverse
 *               (PERM_REVERSE_PETTY_CASH). Each needs PAGE_PETTY_CASH.
 * </pre>
 */
@Controller
@RequestMapping("/petty-cash/vouchers")
public class PettyCashVoucherController {

    private static final String MODULE = PettyCashController.MODULE;
    private static final String BASE = "/petty-cash/vouchers";
    private static final String VIEW = "hasAuthority('PAGE_PETTY_CASH') and hasAuthority('PERM_VIEW_PETTY_CASH')";
    private static final String RECORD = "hasAuthority('PAGE_PETTY_CASH') and hasAuthority('PERM_RECORD_PETTY_CASH')";
    private static final String APPROVE = "hasAuthority('PAGE_PETTY_CASH') and hasAuthority('PERM_APPROVE_PETTY_CASH')";

    private final PettyCashVoucherService vouchers;
    private final PettyCashService pettyCash;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public PettyCashVoucherController(PettyCashVoucherService vouchers, PettyCashService pettyCash, AttachmentService attachmentService,
                                      DataChangeService dataChangeService, ActivityLogService activityLogService, Messages messages, NumberFormats num) {
        this.vouchers = vouchers;
        this.pettyCash = pettyCash;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping("/new")
    @PreAuthorize(RECORD)
    public String create(@RequestParam(required = false) UUID fund, Model model) {
        return form(model, vouchers.newForm(fund), null);
    }

    @PostMapping("/new")
    @PreAuthorize(RECORD)
    public String create(@Valid @ModelAttribute("voucherDto") PettyCashVoucherDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        return save(null, dto, result, model, redirect);
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize(RECORD)
    public String edit(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        PettyCashVoucher v = vouchers.findDetailed(id);
        if (!v.isDraft()) {
            redirect.addFlashAttribute("flashError", messages.get("pettyCash.voucher.notDraft", v.getNumber()));
            return "redirect:" + BASE + "/" + id;
        }
        return form(model, vouchers.formOf(v), v);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize(RECORD)
    public String edit(@PathVariable UUID id, @Valid @ModelAttribute("voucherDto") PettyCashVoucherDto dto, BindingResult result, Model model,
                       RedirectAttributes redirect) {
        return save(id, dto, result, model, redirect);
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(required = false) String tab, @RequestParam(defaultValue = "0") int page, Model model) {
        PettyCashVoucher v = vouchers.findDetailed(id);
        String open = "history".equals(tab) ? tab : "documents";
        model.addAttribute("voucher", v);
        model.addAttribute("journals", vouchers.journals(v));
        model.addAttribute("mine", vouchers.isRecorder(v));
        model.addAttribute("tookPart", vouchers.tookPart(v));
        model.addAttribute("head", vouchers.isHead(v));
        model.addAttribute("balance", v.getFund().isActive() ? pettyCash.balance(v.getFund()) : null);
        model.addAttribute("today", vouchers.today());
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.PETTY_CASH_VOUCHER, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("PettyCashVoucher", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "petty-cash/voucher";
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(RECORD)
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        return step(id, redirect, "SUBMIT_PETTY_CASH", "Submitted", "pettyCash.voucher.submitted", () -> vouchers.submit(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVE)
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        return step(id, redirect, "APPROVE_PETTY_CASH", "Approved", "pettyCash.voucher.approved", () -> vouchers.approve(id, note));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(APPROVE)
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return step(id, redirect, "REJECT_PETTY_CASH", "Rejected", "pettyCash.voucher.rejected",
                () -> AuditContext.withReason(clean(reason), () -> vouchers.reject(id, reason)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(RECORD)
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return step(id, redirect, "CANCEL_PETTY_CASH", "Cancelled", "pettyCash.voucher.cancelled",
                () -> AuditContext.withReason(clean(reason), () -> vouchers.cancel(id, reason)));
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasAuthority('PAGE_PETTY_CASH') and hasAuthority('PERM_REVERSE_PETTY_CASH')")
    public String reverse(@PathVariable UUID id, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reversalDate,
                          @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return step(id, redirect, "REVERSE_PETTY_CASH", "Reversed", "pettyCash.voucher.reversed",
                () -> AuditContext.withReason(clean(reason), () -> vouchers.reverse(id, reversalDate, reason)));
    }

    // ---------------------------------------------------------------- helpers

    private String save(UUID id, PettyCashVoucherDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        PettyCashVoucher existing = id == null ? null : vouchers.findDetailed(id);
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto, existing);
        }
        try {
            PettyCashVoucher v = vouchers.save(id, dto);
            activityLogService.record(MODULE, id == null ? "CREATE_PETTY_CASH" : "UPDATE_PETTY_CASH", (id == null ? "Recorded" : "Changed")
                    + " petty cash voucher " + v.getNumber() + " of " + v.getFund().getCode() + ": " + num.money(v.getAmount()) + " RWF to "
                    + v.getPayee() + " for " + v.getPurpose(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("pettyCash.voucher.saved", v.getNumber()));
            return "redirect:" + BASE + "/" + v.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, id == null ? "CREATE_PETTY_CASH" : "UPDATE_PETTY_CASH", "Failed to save a petty cash voucher: " + error,
                    ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
                model.addAttribute("formErrors", result.getFieldErrors());
            } else {
                model.addAttribute("flashError", error);
            }
            return form(model, dto, existing);
        }
    }

    private String form(Model model, PettyCashVoucherDto dto, PettyCashVoucher voucher) {
        model.addAttribute("voucherDto", dto);
        model.addAttribute("voucher", voucher);
        model.addAttribute("funds", vouchers.myFunds());
        model.addAttribute("costCentres", vouchers.costCentres());
        model.addAttribute("categories", vouchers.categories());
        model.addAttribute("maxPayment", pettyCash.maxPayment());
        model.addAttribute("today", vouchers.today());
        return "petty-cash/voucher-form";
    }

    /** Runs one step of a voucher and logs it, a refusal too. */
    private String step(UUID id, RedirectAttributes redirect, String action, String verb, String successKey, Supplier<PettyCashVoucher> run) {
        try {
            PettyCashVoucher v = run.get();
            String note = v.getDecisionNote() != null && ("REJECT_PETTY_CASH".equals(action) || "CANCEL_PETTY_CASH".equals(action)) ? ": " + v.getDecisionNote()
                    : v.getReversalReason() != null && "REVERSE_PETTY_CASH".equals(action) ? ": " + v.getReversalReason() : "";
            activityLogService.record(MODULE, action, verb + " petty cash voucher " + v.getNumber() + " of " + v.getFund().getCode() + ", "
                    + num.money(v.getAmount()) + " RWF" + note, ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(successKey, v.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed: " + verb.toLowerCase() + " petty cash voucher " + numberOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:" + BASE + "/" + id;
    }

    private String numberOf(UUID id) {
        try {
            return vouchers.findDetailed(id).getNumber();
        } catch (RuntimeException e) {
            return id.toString();
        }
    }

    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
