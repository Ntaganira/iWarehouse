package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.CreditNote;
import com.ntaganira.heritier.iWarehouse.entity.CreditNoteRequest;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoiceLine;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.CreditNoteRequestStatus;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : CreditNoteRequestController.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Credit notes asked for (F-14): the list, a request with what it asks (units back or pieces given up,
 *               the refund, the totals), Finance's review, the approval within the Delegation of Authority, rejection,
 *               withdrawal by whoever asked (reasons kept), issuing the approved credit note, its documents and History.
 *               PAGE_CREDIT_NOTES + PERM_VIEW_CREDIT_NOTE; review PERM_REVIEW_CREDIT_CONTROL; approve PERM_APPROVE_CREDIT_NOTE;
 *               withdraw PERM_RETURN_SALE; issue PERM_ISSUE_CREDIT_NOTE.
 * </pre>
 */
@Controller
@RequestMapping("/credit-notes/requests")
public class CreditNoteRequestController {

    private static final int REASON_MAX = 255;

    private final CreditNoteService creditNoteService;
    private final SalesService salesService;
    private final StockService stockService;
    private final TillService tillService;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public CreditNoteRequestController(CreditNoteService creditNoteService, SalesService salesService, StockService stockService,
                                       TillService tillService, AttachmentService attachmentService, DataChangeService dataChangeService,
                                       ActivityLogService activityLogService, Messages messages, NumberFormats num) {
        this.creditNoteService = creditNoteService;
        this.salesService = salesService;
        this.stockService = stockService;
        this.tillService = tillService;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_CREDIT_NOTES') and hasAuthority('PERM_VIEW_CREDIT_NOTE')")
    public String list(@RequestParam(required = false) String status, @RequestParam(required = false) String search,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("requests", creditNoteService.findRequests(status, search, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", CreditNoteRequestStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("search", search);
        model.addAttribute("paginationQuery", QueryString.of("status", status, "search", search));
        return "credit-notes/requests";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_NOTES') and hasAuthority('PERM_VIEW_CREDIT_NOTE')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "items") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        CreditNoteRequest r = creditNoteService.findRequest(id);
        SalesInvoice invoice = salesService.findDetailed(r.getInvoice().getId());
        String open = List.of("documents", "history").contains(tab) ? tab : "items";
        model.addAttribute("cnr", r);
        model.addAttribute("invoice", invoice);
        model.addAttribute("invoiceLines", invoice.getLines().stream().collect(Collectors.toMap(SalesInvoiceLine::getId, Function.identity())));
        model.addAttribute("units", creditNoteService.requestUnits(r));
        model.addAttribute("cullet", r.getItems().stream().filter(CreditNoteRequest.Item::isCullet).map(CreditNoteRequest.Item::getUnitId)
                .collect(Collectors.toSet()));
        model.addAttribute("sizes", r.getSizes().stream().sorted(Comparator.comparingInt(sz -> lineNo(invoice, sz.getLineId()))).toList());
        model.addAttribute("place", r.getLocationId() == null ? null : stockService.locationsById().get(r.getLocationId()));
        model.addAttribute("creditNote", r.getCreditNoteId() == null ? null : creditNoteService.findDetailed(r.getCreditNoteId()));
        model.addAttribute("mine", CreditNoteService.isRequester(r));
        model.addAttribute("reviewer", CreditNoteService.isReviewer(r));
        model.addAttribute("authority", r.getStatus() == CreditNoteRequestStatus.PENDING_APPROVAL ? creditNoteService.authorityOf(r) : null);
        model.addAttribute("myTill", r.getStatus() == CreditNoteRequestStatus.APPROVED && r.getRefundMethod() == PaymentMethod.CASH
                ? tillService.current().map(tillService::summary).orElse(null) : null);
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.CREDIT_NOTE_REQUEST, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("CreditNoteRequest", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "credit-notes/request";
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_NOTES') and hasAuthority('PERM_REVIEW_CREDIT_CONTROL')")
    public String review(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            CreditNoteRequest r = creditNoteService.review(id, note);
            activityLogService.record(PosController.MODULE, "REVIEW_CREDIT_NOTE_REQUEST", "Reviewed credit note request " + r.getNumber()
                    + " of " + r.getRequestedBy() + (StringUtils.hasText(note) ? ": " + note.trim() : ""), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("creditNote.request.reviewed", r.getNumber()));
        } catch (BusinessException e) {
            failed(id, "REVIEW_CREDIT_NOTE_REQUEST", "review", e, redirect);
        }
        return "redirect:/credit-notes/requests/" + id;
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_NOTES') and hasAuthority('PERM_APPROVE_CREDIT_NOTE')")
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            CreditNoteRequest r = creditNoteService.approve(id, note);
            activityLogService.record(PosController.MODULE, "APPROVE_CREDIT_NOTE_REQUEST", "Approved credit note request " + r.getNumber()
                    + " of " + r.getRequestedBy() + " on " + r.getInvoice().getNumber() + ": " + num.money(r.getTotalAmount()) + " RWF"
                    + (r.getDecisionNote() != null ? " (" + r.getDecisionNote() + ")" : ""), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("creditNote.request.approved", r.getNumber()));
        } catch (BusinessException e) {
            failed(id, "APPROVE_CREDIT_NOTE_REQUEST", "approve", e, redirect);
        }
        return "redirect:/credit-notes/requests/" + id;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_NOTES') and (hasAuthority('PERM_APPROVE_CREDIT_NOTE') or hasAuthority('PERM_REVIEW_CREDIT_CONTROL'))")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return withReason(id, reason, redirect, true);
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_NOTES') and hasAuthority('PERM_RETURN_SALE')")
    public String withdraw(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return withReason(id, reason, redirect, false);
    }

    /** Issues the approved credit note: the glass comes back, the refund is paid (cash from the issuer's till). */
    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_NOTES') and hasAuthority('PERM_ISSUE_CREDIT_NOTE')")
    public String issue(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            CreditNote note = creditNoteService.issueApproved(id);
            CreditNoteRequest r = creditNoteService.findRequest(id);
            activityLogService.record(PosController.MODULE, "CREATE_CREDIT_NOTE", "Issued credit note " + note.getNumber()
                    + " from request " + r.getNumber() + " on " + r.getInvoice().getNumber() + ": " + num.money(note.getTotalAmount()) + " RWF"
                    + (note.isRefunded() ? ", refunded " + num.money(note.getRefundAmount()) + " RWF by " + note.getRefundMethod() : "")
                    + ": " + note.getReason(), ActivityStatus.SUCCESS);
            String text = messages.get("creditNote.issued", note.getNumber(), num.money(note.getTotalAmount()));
            if (note.getRefundMethod() == PaymentMethod.CASH) {
                text = text + ". " + messages.get("creditNote.giveCash", num.money(note.getRefundAmount()));
            }
            redirect.addFlashAttribute("flashSuccess", text);
            return "redirect:/credit-notes/" + note.getId();
        } catch (BusinessException e) {
            failed(id, "CREATE_CREDIT_NOTE", "issue the credit note of", e, redirect);
            return "redirect:/credit-notes/requests/" + id;
        }
    }

    private String withReason(UUID id, String reason, RedirectAttributes redirect, boolean reject) {
        String action = reject ? "REJECT_CREDIT_NOTE_REQUEST" : "CANCEL_CREDIT_NOTE_REQUEST";
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/credit-notes/requests/" + id;
        }
        try {
            CreditNoteRequest r = AuditContext.withReason(reason.trim(),
                    () -> reject ? creditNoteService.reject(id, reason) : creditNoteService.withdraw(id, reason));
            activityLogService.record(PosController.MODULE, action, (reject ? "Rejected" : "Withdrew") + " credit note request "
                    + r.getNumber() + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(reject ? "creditNote.request.rejectedMsg" : "creditNote.request.withdrawnMsg",
                    r.getNumber()));
        } catch (BusinessException e) {
            failed(id, action, reject ? "reject" : "withdraw", e, redirect);
        }
        return "redirect:/credit-notes/requests/" + id;
    }

    private void failed(UUID id, String action, String verb, BusinessException e, RedirectAttributes redirect) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        activityLogService.record(PosController.MODULE, action, "Failed to " + verb + " credit note request " + numberOf(id) + ": " + error,
                ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }

    private String numberOf(UUID id) {
        try {
            return creditNoteService.findRequest(id).getNumber();
        } catch (RuntimeException e) {
            return id.toString();
        }
    }

    private static int lineNo(SalesInvoice invoice, UUID lineId) {
        return invoice.getLines().stream().filter(l -> l.getId().equals(lineId)).mapToInt(SalesInvoiceLine::getLineNo).findFirst().orElse(0);
    }
}
