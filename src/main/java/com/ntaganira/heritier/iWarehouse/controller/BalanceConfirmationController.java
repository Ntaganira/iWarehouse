package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.BalanceConfirmation;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.ConfirmationStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.AttachmentService;
import com.ntaganira.heritier.iWarehouse.service.BalanceConfirmationService;
import com.ntaganira.heritier.iWarehouse.service.CustomerAccountService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.PdfService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : BalanceConfirmationController.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Balance confirmations (SDP 10-11): the list by status, sending one to a customer with a balance or to all
 *               of them, a confirmation with its letter and reply slip (A4 PDF), the reply (agreed or disputed), its
 *               documents (the signed reply) and History. PAGE_BALANCE_CONFIRMATIONS + PERM_VIEW_BALANCE_CONFIRMATION;
 *               sending and replies PERM_CONFIRM_BALANCES.
 * </pre>
 */
@Controller
@RequestMapping("/accounting/confirmations")
public class BalanceConfirmationController {

    static final String MODULE = "Balance Confirmations";
    private static final String VIEW = "hasAuthority('PAGE_BALANCE_CONFIRMATIONS') and hasAuthority('PERM_VIEW_BALANCE_CONFIRMATION')";
    private static final String SEND = "hasAuthority('PAGE_BALANCE_CONFIRMATIONS') and hasAuthority('PERM_CONFIRM_BALANCES')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final BalanceConfirmationService confirmationService;
    private final CustomerAccountService accountService;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final PdfService pdfService;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public BalanceConfirmationController(BalanceConfirmationService confirmationService, CustomerAccountService accountService,
                                         AttachmentService attachmentService, DataChangeService dataChangeService, PdfService pdfService,
                                         ReportFiles reportFiles, ActivityLogService activityLogService, Messages messages) {
        this.confirmationService = confirmationService;
        this.accountService = accountService;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.pdfService = pdfService;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("confirmations", confirmationService.findPage(status, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", ConfirmationStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("waiting", confirmationService.countByStatus(ConfirmationStatus.SENT));
        model.addAttribute("disputed", confirmationService.countByStatus(ConfirmationStatus.DISAGREED));
        model.addAttribute("paginationQuery", QueryString.of("status", status));
        return "confirmations/list";
    }

    @GetMapping("/new")
    @PreAuthorize(SEND)
    public String form(Model model) {
        model.addAttribute("customers", confirmationService.candidates());
        model.addAttribute("today", confirmationService.today());
        if (!model.containsAttribute("asOf")) {
            model.addAttribute("asOf", YearMonth.from(confirmationService.today()).minusMonths(1).atEndOfMonth());
        }
        return "confirmations/form";
    }

    @PostMapping("/new")
    @PreAuthorize(SEND)
    public String send(@RequestParam(required = false) UUID customerId,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                       @RequestParam(defaultValue = "false") boolean all, Model model, RedirectAttributes redirect) {
        try {
            if (all) {
                int sent = confirmationService.sendAll(asOf);
                activityLogService.record(MODULE, "CREATE_BALANCE_CONFIRMATION", "Sent " + sent + " balance confirmation(s) of "
                        + asOf.format(DAY), ActivityStatus.SUCCESS);
                redirect.addFlashAttribute("flashSuccess", messages.get("confirmation.sentAll", sent, asOf.format(DAY)));
                return "redirect:/accounting/confirmations?status=SENT";
            }
            if (customerId == null) {
                throw BusinessException.onField("customerId", "confirmation.customer.required");
            }
            BalanceConfirmation c = confirmationService.send(customerId, asOf);
            activityLogService.record(MODULE, "CREATE_BALANCE_CONFIRMATION", "Sent balance confirmation " + c.getNumber() + " to "
                    + c.getCustomer().getName() + ": " + c.getBalance().toPlainString() + " RWF at " + asOf.format(DAY), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("confirmation.sent", c.getNumber()));
            return "redirect:/accounting/confirmations/" + c.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_BALANCE_CONFIRMATION", "Failed to send a balance confirmation: " + error, ActivityStatus.FAILED);
            model.addAttribute("flashError", error);
            model.addAttribute("errorField", e.getField());
            model.addAttribute("customerId", customerId);
            model.addAttribute("asOf", asOf);
            return form(model);
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "documents") String tab, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        BalanceConfirmation c = confirmationService.findDetailed(id);
        String open = "history".equals(tab) ? tab : "documents";
        model.addAttribute("confirmation", c);
        model.addAttribute("today", confirmationService.today());
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.BALANCE_CONFIRMATION, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("BalanceConfirmation", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "confirmations/view";
    }

    @PostMapping("/{id}/reply")
    @PreAuthorize(SEND)
    public String reply(@PathVariable UUID id, @RequestParam(defaultValue = "true") boolean agreed,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate repliedOn,
                        @RequestParam(required = false) BigDecimal replyBalance, @RequestParam(required = false) String replyNote,
                        RedirectAttributes redirect) {
        try {
            BalanceConfirmation c = confirmationService.reply(id, new BalanceConfirmationService.Reply(agreed, repliedOn, replyBalance, replyNote));
            activityLogService.record(MODULE, "UPDATE_BALANCE_CONFIRMATION", "Recorded the reply to " + c.getNumber() + ": "
                    + (agreed ? "agreed" : "disputed, the customer states " + c.getReplyBalance().toPlainString() + " RWF (" + c.getReplyNote() + ")"),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute(agreed ? "flashSuccess" : "flashWarning", messages.get(agreed ? "confirmation.agreedMsg" : "confirmation.disputedMsg",
                    c.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_BALANCE_CONFIRMATION", "Failed to record the reply to confirmation " + id + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/accounting/confirmations/" + id;
    }

    /** The confirmation letter with its reply slip, on A4 with the letterhead. */
    @GetMapping("/{id}/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        BalanceConfirmation c = confirmationService.findDetailed(id);
        Map<String, Object> model = reportFiles.letterhead();
        model.put("confirmation", c);
        byte[] body = pdfService.render("confirmations/pdf", model);
        activityLogService.record(MODULE, "EXPORT_BALANCE_CONFIRMATION", "Downloaded balance confirmation " + c.getNumber() + " to PDF",
                ActivityStatus.SUCCESS);
        return ReportFiles.file(c.getNumber() + ".pdf", MediaType.APPLICATION_PDF, body);
    }
}
