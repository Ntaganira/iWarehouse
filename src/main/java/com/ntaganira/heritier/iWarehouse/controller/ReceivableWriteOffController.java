package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.WriteOffDto;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.ReceivableWriteOff;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.WriteOffStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : ReceivableWriteOffController.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Receivable write-offs (F-14): the list, the form (opened from a customer's account), a write-off with its
 *               review, approval within the Delegation of Authority (posting bad debts), rejection and withdrawal (reasons
 *               kept), its journal, documents and History. PAGE_RECEIVABLE_WRITE_OFFS + PERM_VIEW_WRITE_OFF; prepare
 *               PERM_PREPARE_WRITE_OFF; review PERM_REVIEW_CREDIT_CONTROL; approve PERM_APPROVE_WRITE_OFF.
 * </pre>
 */
@Controller
@RequestMapping("/accounting/write-offs")
public class ReceivableWriteOffController {

    private static final int REASON_MAX = 255;

    private final ReceivableWriteOffService writeOffService;
    private final CustomerService customerService;
    private final CustomerAgeing ageing;
    private final JournalService journalService;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public ReceivableWriteOffController(ReceivableWriteOffService writeOffService, CustomerService customerService, CustomerAgeing ageing,
                                        JournalService journalService, AttachmentService attachmentService,
                                        DataChangeService dataChangeService, ActivityLogService activityLogService, Messages messages,
                                        NumberFormats num) {
        this.writeOffService = writeOffService;
        this.customerService = customerService;
        this.ageing = ageing;
        this.journalService = journalService;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_RECEIVABLE_WRITE_OFFS') and hasAuthority('PERM_VIEW_WRITE_OFF')")
    public String list(@RequestParam(required = false) String status, @RequestParam(required = false) String search,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("writeOffs", writeOffService.findPage(status, search, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", WriteOffStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("search", search);
        model.addAttribute("paginationQuery", QueryString.of("status", status, "search", search));
        return "write-offs/list";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_RECEIVABLE_WRITE_OFFS') and hasAuthority('PERM_PREPARE_WRITE_OFF')")
    public String form(@RequestParam UUID customer, Model model) {
        return form(model, customerOf(customer), new WriteOffDto());
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_RECEIVABLE_WRITE_OFFS') and hasAuthority('PERM_PREPARE_WRITE_OFF')")
    public String prepare(@RequestParam UUID customer, @Valid @ModelAttribute("writeOffDto") WriteOffDto dto, BindingResult result,
                          Model model, RedirectAttributes redirect) {
        Customer c = customerOf(customer);
        if (result.hasErrors()) {
            return form(model, c, dto);
        }
        try {
            ReceivableWriteOff w = writeOffService.prepare(customer, new ReceivableWriteOffService.Form(dto.getAmount(), dto.getInvoiceId(),
                    dto.getReason(), dto.getRecoveryAction()));
            activityLogService.record(AccountingController.MODULE, "CREATE_WRITE_OFF", "Asked to write off " + num.money(w.getAmount())
                    + " RWF owed by " + c.getName() + " (" + w.getNumber() + "): " + w.getReason(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("writeOff.created", w.getNumber()));
            return "redirect:/accounting/write-offs/" + w.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(AccountingController.MODULE, "CREATE_WRITE_OFF", "Failed to ask for a write-off of what " + c.getName()
                    + " owes: " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
            } else {
                model.addAttribute("flashError", error);
            }
            return form(model, c, dto);
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_RECEIVABLE_WRITE_OFFS') and hasAuthority('PERM_VIEW_WRITE_OFF')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "documents") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        ReceivableWriteOff w = writeOffService.findDetailed(id);
        String open = "history".equals(tab) ? tab : "documents";
        model.addAttribute("writeOff", w);
        model.addAttribute("owedNow", writeOffService.owed(w.getCustomer().getId()));
        model.addAttribute("mine", ReceivableWriteOffService.isRequester(w));
        model.addAttribute("reviewer", ReceivableWriteOffService.isReviewer(w));
        model.addAttribute("authority", w.getStatus() == WriteOffStatus.PENDING_APPROVAL ? writeOffService.authorityOf(w) : null);
        model.addAttribute("journals", journalService.forSource(id, JournalSource.RECEIVABLE_WRITE_OFF));
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.RECEIVABLE_WRITE_OFF, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("ReceivableWriteOff", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "write-offs/view";
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('PAGE_RECEIVABLE_WRITE_OFFS') and hasAuthority('PERM_REVIEW_CREDIT_CONTROL')")
    public String review(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            ReceivableWriteOff w = writeOffService.review(id, note);
            activityLogService.record(AccountingController.MODULE, "REVIEW_WRITE_OFF", "Reviewed write-off " + w.getNumber() + " of "
                    + w.getRequestedBy() + (StringUtils.hasText(note) ? ": " + note.trim() : ""), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("writeOff.reviewed", w.getNumber()));
        } catch (BusinessException e) {
            failed(id, "REVIEW_WRITE_OFF", "review", e, redirect);
        }
        return "redirect:/accounting/write-offs/" + id;
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PAGE_RECEIVABLE_WRITE_OFFS') and hasAuthority('PERM_APPROVE_WRITE_OFF')")
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            ReceivableWriteOff w = writeOffService.approve(id, note);
            activityLogService.record(AccountingController.MODULE, "APPROVE_WRITE_OFF", "Approved write-off " + w.getNumber() + " of "
                    + w.getRequestedBy() + ": " + num.money(w.getAmount()) + " RWF owed by " + w.getCustomer().getName() + " posted to bad debts"
                    + (w.getDecisionNote() != null ? " (" + w.getDecisionNote() + ")" : ""), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("writeOff.approved", w.getNumber()));
        } catch (BusinessException e) {
            failed(id, "APPROVE_WRITE_OFF", "approve", e, redirect);
        }
        return "redirect:/accounting/write-offs/" + id;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAGE_RECEIVABLE_WRITE_OFFS') and (hasAuthority('PERM_APPROVE_WRITE_OFF') or hasAuthority('PERM_REVIEW_CREDIT_CONTROL'))")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return withReason(id, reason, redirect, true);
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('PAGE_RECEIVABLE_WRITE_OFFS') and hasAuthority('PERM_PREPARE_WRITE_OFF')")
    public String withdraw(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return withReason(id, reason, redirect, false);
    }

    private String withReason(UUID id, String reason, RedirectAttributes redirect, boolean reject) {
        String action = reject ? "REJECT_WRITE_OFF" : "CANCEL_WRITE_OFF";
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/accounting/write-offs/" + id;
        }
        try {
            ReceivableWriteOff w = AuditContext.withReason(reason.trim(),
                    () -> reject ? writeOffService.reject(id, reason) : writeOffService.withdraw(id, reason));
            activityLogService.record(AccountingController.MODULE, action, (reject ? "Rejected" : "Withdrew") + " write-off " + w.getNumber()
                    + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(reject ? "writeOff.rejectedMsg" : "writeOff.withdrawnMsg", w.getNumber()));
        } catch (BusinessException e) {
            failed(id, action, reject ? "reject" : "withdraw", e, redirect);
        }
        return "redirect:/accounting/write-offs/" + id;
    }

    private String form(Model model, Customer customer, WriteOffDto dto) {
        model.addAttribute("customer", customer);
        model.addAttribute("writeOffDto", dto);
        model.addAttribute("owed", writeOffService.owed(customer.getId()));
        model.addAttribute("overdue", ageing.overdue(customer, 0));
        model.addAttribute("invoices", writeOffService.invoicesOf(customer.getId()));
        model.addAttribute("open", writeOffService.ofCustomer(customer.getId()).stream().filter(ReceivableWriteOff::isWaiting).toList());
        return "write-offs/form";
    }

    /** Write-offs are of account customers: the walk-in never owes on account. */
    private Customer customerOf(UUID id) {
        Customer c = customerService.findById(id);
        if (!c.getType().isCreditAllowed()) {
            throw new NotFoundException("Customer", id);
        }
        return c;
    }

    private void failed(UUID id, String action, String verb, BusinessException e, RedirectAttributes redirect) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        activityLogService.record(AccountingController.MODULE, action, "Failed to " + verb + " write-off " + numberOf(id) + ": " + error,
                ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }

    private String numberOf(UUID id) {
        try {
            return writeOffService.findDetailed(id).getNumber();
        } catch (RuntimeException e) {
            return id.toString();
        }
    }
}
