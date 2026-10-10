package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.ManualJournalDto;
import com.ntaganira.heritier.iWarehouse.entity.ManualJournal;
import com.ntaganira.heritier.iWarehouse.entity.ManualJournalLine;
import com.ntaganira.heritier.iWarehouse.enums.AccountType;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.ManualJournalStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.ManualJournalService;
import jakarta.validation.Validator;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : ManualJournalController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Manual journals (ACC-05): the list, asking for one (or a copy of another), a manual journal with its
 *               lines, journals and History; approving or rejecting it (another person), withdrawing it (the
 *               requester), reversing it once posted. PAGE_MANUAL_JOURNALS + PERM_VIEW_ACCOUNTING; asking
 *               PERM_CREATE_MANUAL_JOURNAL, deciding PERM_APPROVE_MANUAL_JOURNAL, reversing PERM_REVERSE_MANUAL_JOURNAL.
 * </pre>
 */
@Controller
@RequestMapping("/accounting/manual-journals")
public class ManualJournalController {

    private static final int REASON_MAX = 255;
    private static final String BASE = "/accounting/manual-journals";

    private final ManualJournalService journalService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final SpringValidatorAdapter validator;
    private final Messages messages;
    private final NumberFormats num;

    public ManualJournalController(ManualJournalService journalService, DataChangeService dataChangeService,
                                   ActivityLogService activityLogService, Validator validator, Messages messages, NumberFormats num) {
        this.journalService = journalService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.validator = new SpringValidatorAdapter(validator);
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_MANUAL_JOURNALS') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("journals", journalService.findPage(search, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", ManualJournalStatus.values());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("pending", journalService.pendingCount());
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "manual-journals/list";
    }

    /** Asks for a manual journal: empty, or a copy of another one's description and lines. */
    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_MANUAL_JOURNALS') and hasAuthority('PERM_CREATE_MANUAL_JOURNAL')")
    public String form(@RequestParam(required = false) UUID copy, Model model) {
        return form(model, journalService.newForm(copy));
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_MANUAL_JOURNALS') and hasAuthority('PERM_CREATE_MANUAL_JOURNAL')")
    public String create(@ModelAttribute("mjDto") ManualJournalDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        dto.getLines().removeIf(ManualJournalDto.Line::isBlank);
        validator.validate(dto, result);
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            ManualJournal journal = journalService.create(dto);
            activityLogService.record(AccountingController.MODULE, "CREATE_MANUAL_JOURNAL", "Asked for manual journal " + journal.getNumber()
                    + " of " + journal.getEntryDate() + ", " + num.money(journal.getTotal()) + " RWF: " + journal.getDescription(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("manualJournal.created", journal.getNumber()));
            return "redirect:" + BASE + "/" + journal.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(AccountingController.MODULE, "CREATE_MANUAL_JOURNAL", "Failed to ask for a manual journal: " + error,
                    ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
            } else {
                model.addAttribute("flashError", error);
            }
            return invalid(model, dto, result);
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_MANUAL_JOURNALS') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        ManualJournal journal = journalService.findById(id);
        List<ManualJournalLine> lines = journalService.lines(id);
        model.addAttribute("mj", journal);
        model.addAttribute("lines", lines);
        model.addAttribute("journals", journalService.journals(journal));
        model.addAttribute("mine", journalService.isRequester(journal));
        model.addAttribute("today", journalService.today());
        model.addAttribute("history", dataChangeService.history("ManualJournal", id.toString(), Paging.page(page), Paging.SIZE));
        return "manual-journals/view";
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PAGE_MANUAL_JOURNALS') and hasAuthority('PERM_APPROVE_MANUAL_JOURNAL')")
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        if (note != null && note.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:" + BASE + "/" + id;
        }
        try {
            ManualJournal journal = journalService.approve(id, note);
            activityLogService.record(AccountingController.MODULE, "APPROVE_MANUAL_JOURNAL", "Approved manual journal " + journal.getNumber()
                    + " of " + journal.getRequestedBy() + ", " + num.money(journal.getTotal()) + " RWF: " + journal.getDescription(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("manualJournal.approved", journal.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(AccountingController.MODULE, "APPROVE_MANUAL_JOURNAL", "Failed to approve manual journal " + numberOf(id)
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:" + BASE + "/" + id;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAGE_MANUAL_JOURNALS') and hasAuthority('PERM_APPROVE_MANUAL_JOURNAL')")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return decideWithReason(id, reason, redirect, true);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_MANUAL_JOURNALS') and hasAuthority('PERM_CREATE_MANUAL_JOURNAL')")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return decideWithReason(id, reason, redirect, false);
    }

    /** Reverses a posted manual journal on a day (today by default), with a reason. */
    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasAuthority('PAGE_MANUAL_JOURNALS') and hasAuthority('PERM_REVERSE_MANUAL_JOURNAL')")
    public String reverse(@PathVariable UUID id, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reversalDate,
                          @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:" + BASE + "/" + id;
        }
        try {
            ManualJournal journal = AuditContext.withReason(reason.trim(), () -> journalService.reverse(id, reversalDate, reason));
            activityLogService.record(AccountingController.MODULE, "REVERSE_MANUAL_JOURNAL", "Reversed manual journal " + journal.getNumber()
                    + " on " + journal.getReversalDate() + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("manualJournal.reversed", journal.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(AccountingController.MODULE, "REVERSE_MANUAL_JOURNAL", "Failed to reverse manual journal " + numberOf(id)
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:" + BASE + "/" + id;
    }

    // ---------------------------------------------------------------- helpers

    private String decideWithReason(UUID id, String reason, RedirectAttributes redirect, boolean reject) {
        String action = reject ? "REJECT_MANUAL_JOURNAL" : "CANCEL_MANUAL_JOURNAL";
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:" + BASE + "/" + id;
        }
        try {
            ManualJournal journal = AuditContext.withReason(reason.trim(),
                    () -> reject ? journalService.reject(id, reason) : journalService.cancel(id, reason));
            activityLogService.record(AccountingController.MODULE, action, (reject ? "Rejected" : "Withdrew") + " manual journal "
                    + journal.getNumber() + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(reject ? "manualJournal.rejectedMsg" : "manualJournal.cancelledMsg",
                    journal.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(AccountingController.MODULE, action, "Failed to " + (reject ? "reject" : "withdraw") + " manual journal "
                    + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:" + BASE + "/" + id;
    }

    private String form(Model model, ManualJournalDto dto) {
        model.addAttribute("mjDto", dto);
        model.addAttribute("accounts", journalService.accounts());
        model.addAttribute("accountTypes", AccountType.values());
        model.addAttribute("controlled", journalService.controlledAccounts());
        model.addAttribute("today", journalService.today());
        model.addAttribute("openFrom", journalService.openFrom());
        return "manual-journals/form";
    }

    private String invalid(Model model, ManualJournalDto dto, BindingResult result) {
        while (dto.getLines().size() < 2) {
            dto.getLines().add(new ManualJournalDto.Line());
        }
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    private String numberOf(UUID id) {
        try {
            return journalService.findById(id).getNumber();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }
}
