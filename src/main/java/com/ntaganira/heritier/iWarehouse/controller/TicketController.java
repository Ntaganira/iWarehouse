package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.TransactionTicket;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.TicketKind;
import com.ntaganira.heritier.iWarehouse.enums.TicketStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : TicketController.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Transaction Tickets (BP 7.4, F-05, Annex I, decision D6): the list (tickets due are raised as it opens), a
 *               ticket with the units it covers and its History; Supply Chain verifies it, Commercial countersigns it,
 *               Finance posts it (a goods receipt waiting for its ticket posts then), and any of them may reject it with a
 *               reason. PAGE_TRANSACTION_TICKETS + PERM_VIEW_TICKET; PERM_VERIFY_TICKET, PERM_COUNTERSIGN_TICKET,
 *               PERM_POST_TICKET.
 * </pre>
 */
@Controller
@RequestMapping("/tickets")
public class TicketController {

    static final String MODULE = "Transaction Tickets";
    private static final String VIEW = "hasAuthority('PAGE_TRANSACTION_TICKETS') and hasAuthority('PERM_VIEW_TICKET')";

    private final TicketService ticketService;
    private final TicketPostingService postingService;
    private final SiteTransferService siteTransferService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public TicketController(TicketService ticketService, TicketPostingService postingService, SiteTransferService siteTransferService,
                            DataChangeService dataChangeService, ActivityLogService activityLogService, Messages messages) {
        this.ticketService = ticketService;
        this.postingService = postingService;
        this.siteTransferService = siteTransferService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String list(@RequestParam(required = false) String status, @RequestParam(required = false) String kind,
                       @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page, Model model) {
        ticketService.raiseDue();
        model.addAttribute("tickets", ticketService.findPage(status, kind, search, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", TicketStatus.values());
        model.addAttribute("kinds", TicketKind.values());
        model.addAttribute("status", status);
        model.addAttribute("kind", kind);
        model.addAttribute("search", search);
        model.addAttribute("enabled", ticketService.enabled());
        model.addAttribute("gate", ticketService.gateReceipts());
        model.addAttribute("paginationQuery", QueryString.of("status", status, "kind", kind, "search", search));
        return "tickets/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "units") String tab, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        TransactionTicket t = ticketService.find(id);
        String open = "history".equals(tab) ? tab : "units";
        model.addAttribute("ticket", t);
        model.addAttribute("moved", ticketService.movementsOf(t));
        model.addAttribute("locations", siteTransferService.locations());
        model.addAttribute("raiser", TicketService.isRaiser(t));
        model.addAttribute("verifier", TicketService.isVerifier(t));
        model.addAttribute("countersigner", TicketService.isCountersigner(t));
        model.addAttribute("history", dataChangeService.history("TransactionTicket", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "tickets/view";
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAuthority('PAGE_TRANSACTION_TICKETS') and hasAuthority('PERM_VERIFY_TICKET')")
    public String verify(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            TransactionTicket t = ticketService.verify(id, note);
            log("VERIFY_TICKET", "Verified ticket " + t.getNumber() + " of " + t.getSourceNumber(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("ticket.verified", t.getNumber()));
        } catch (BusinessException e) {
            failed(id, "VERIFY_TICKET", "verify", e, redirect);
        }
        return "redirect:/tickets/" + id;
    }

    @PostMapping("/{id}/countersign")
    @PreAuthorize("hasAuthority('PAGE_TRANSACTION_TICKETS') and hasAuthority('PERM_COUNTERSIGN_TICKET')")
    public String countersign(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            TransactionTicket t = ticketService.countersign(id, note);
            log("APPROVE_TICKET", "Countersigned ticket " + t.getNumber() + " of " + t.getSourceNumber(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("ticket.countersigned", t.getNumber()));
        } catch (BusinessException e) {
            failed(id, "APPROVE_TICKET", "countersign", e, redirect);
        }
        return "redirect:/tickets/" + id;
    }

    @PostMapping("/{id}/post")
    @PreAuthorize("hasAuthority('PAGE_TRANSACTION_TICKETS') and hasAuthority('PERM_POST_TICKET')")
    public String post(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            TicketPostingService.Posted posted = postingService.post(id);
            TransactionTicket t = posted.ticket();
            log("POST_TICKET", "Posted ticket " + t.getNumber() + " of " + t.getSourceNumber()
                    + (posted.receipt() != null ? ": goods receipt posted with it" : ""), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(posted.receipt() != null ? "ticket.postedReceipt" : "ticket.posted",
                    t.getNumber(), t.getSourceNumber()));
        } catch (BusinessException e) {
            failed(id, "POST_TICKET", "post", e, redirect);
        }
        return "redirect:/tickets/" + id;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAGE_TRANSACTION_TICKETS') and (hasAuthority('PERM_VERIFY_TICKET') or hasAuthority('PERM_COUNTERSIGN_TICKET') or hasAuthority('PERM_POST_TICKET'))")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > 255) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/tickets/" + id;
        }
        try {
            TransactionTicket t = AuditContext.withReason(reason.trim(), () -> ticketService.reject(id, reason));
            log("REJECT_TICKET", "Rejected ticket " + t.getNumber() + " of " + t.getSourceNumber() + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("ticket.rejectedMsg", t.getNumber()));
        } catch (BusinessException e) {
            failed(id, "REJECT_TICKET", "reject", e, redirect);
        }
        return "redirect:/tickets/" + id;
    }

    private void log(String action, String text, ActivityStatus status) {
        activityLogService.record(MODULE, action, text, status);
    }

    private void failed(UUID id, String action, String verb, BusinessException e, RedirectAttributes redirect) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        String number;
        try {
            number = ticketService.find(id).getNumber();
        } catch (RuntimeException ex) {
            number = id.toString();
        }
        log(action, "Failed to " + verb + " ticket " + number + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }
}
