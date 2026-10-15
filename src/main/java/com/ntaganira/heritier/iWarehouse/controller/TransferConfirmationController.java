package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.PaymentConfirmation;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.TransferConfirmationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : TransferConfirmationController.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Bank transfers to confirm (SDP 14): the transfers paid on invoices in the last 90 days, waiting first;
 *               Finance confirms one with the bank's reference, and its goods may then leave. PAGE_TRANSFER_CONFIRMATIONS
 *               + PERM_CONFIRM_TRANSFER or PERM_VIEW_INVOICE; confirming PERM_CONFIRM_TRANSFER.
 * </pre>
 */
@Controller
@RequestMapping("/transfers")
public class TransferConfirmationController {

    static final String MODULE = "Transfers";

    private final TransferConfirmationService transferService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public TransferConfirmationController(TransferConfirmationService transferService, ActivityLogService activityLogService, Messages messages) {
        this.transferService = transferService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_TRANSFER_CONFIRMATIONS') and (hasAuthority('PERM_CONFIRM_TRANSFER') or hasAuthority('PERM_VIEW_INVOICE'))")
    public String list(@RequestParam(defaultValue = "waiting") String show, @RequestParam(defaultValue = "0") int page, Model model) {
        boolean waiting = !"all".equals(show);
        model.addAttribute("transfers", Paging.of(transferService.recent(waiting), Paging.page(page)));
        model.addAttribute("show", waiting ? "waiting" : "all");
        model.addAttribute("paginationQuery", QueryString.of("show", waiting ? null : "all"));
        return "transfers/list";
    }

    @PostMapping("/{paymentId}/confirm")
    @PreAuthorize("hasAuthority('PAGE_TRANSFER_CONFIRMATIONS') and hasAuthority('PERM_CONFIRM_TRANSFER')")
    public String confirm(@PathVariable UUID paymentId, @RequestParam(required = false) String bankReference,
                          @RequestParam(required = false) String note, @RequestParam(required = false) UUID invoice, RedirectAttributes redirect) {
        try {
            PaymentConfirmation c = transferService.confirm(paymentId, bankReference, note);
            activityLogService.record(MODULE, "APPROVE_TRANSFER", "Confirmed the transfer paid on invoice " + c.getInvoiceId()
                    + (c.getBankReference() == null ? "" : ", bank reference " + c.getBankReference()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("bankTransfer.confirmedMsg"));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "APPROVE_TRANSFER", "Failed to confirm transfer " + paymentId + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return invoice != null ? "redirect:/invoices/" + invoice : "redirect:/transfers";
    }
}
