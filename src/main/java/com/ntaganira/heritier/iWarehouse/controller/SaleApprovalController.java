package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.SaleApproval;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.SaleApprovalStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.SaleApprovalService;
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
 * - File      : SaleApprovalController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Approval requests from the counter (POS-05, POS-06): the list, a request with its before and
 *               after values and History; approve, or reject with a reason, never your own. PAGE_SALE_APPROVALS +
 *               PERM_VIEW_INVOICE; deciding PERM_APPROVE_SALE.
 * </pre>
 */
@Controller
@RequestMapping("/sale-approvals")
public class SaleApprovalController {

    private static final int REASON_MAX = 200;

    private final SaleApprovalService approvalService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public SaleApprovalController(SaleApprovalService approvalService, DataChangeService dataChangeService,
                                  ActivityLogService activityLogService, Messages messages, NumberFormats num) {
        this.approvalService = approvalService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_SALE_APPROVALS') and hasAuthority('PERM_VIEW_INVOICE')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("approvals", approvalService.findPage(status, search, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", SaleApprovalStatus.values());
        model.addAttribute("pending", approvalService.pendingCount());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "sale-approvals/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_SALE_APPROVALS') and hasAuthority('PERM_VIEW_INVOICE')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "details") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        String open = "history".equals(tab) ? tab : "details";
        SaleApproval approval = approvalService.findDetailed(id);
        model.addAttribute("approval", approval);
        model.addAttribute("line", approvalService.lineOf(approval).orElse(null));
        model.addAttribute("mine", SaleApprovalService.isRequester(approval));
        model.addAttribute("history", dataChangeService.history("SaleApproval", id.toString(), Paging.pageOf("history", open, page),
                Paging.SIZE));
        model.addAttribute("tab", open);
        return "sale-approvals/view";
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PAGE_SALE_APPROVALS') and hasAuthority('PERM_APPROVE_SALE')")
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            String reason = approvalService.findDetailed(id).getReason();
            // The line's new price carries the requester's reason (Audit rule 4)
            SaleApproval approval = AuditContext.withReason(reason, () -> approvalService.approve(id, note));
            activityLogService.record(PosController.MODULE, "APPROVE_SALE_APPROVAL", "Approved " + approval.getNumber() + " of "
                    + approval.getRequestedBy() + ": " + describe(approval), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("saleApproval.approvedMsg", approval.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(PosController.MODULE, "APPROVE_SALE_APPROVAL", "Failed to approve " + numberOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/sale-approvals/" + id;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAGE_SALE_APPROVALS') and hasAuthority('PERM_APPROVE_SALE')")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/sale-approvals/" + id;
        }
        try {
            SaleApproval approval = AuditContext.withReason(reason.trim(), () -> approvalService.reject(id, reason));
            activityLogService.record(PosController.MODULE, "REJECT_SALE_APPROVAL", "Rejected " + approval.getNumber() + " of "
                    + approval.getRequestedBy() + " (" + describe(approval) + "): " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("saleApproval.rejectedMsg", approval.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(PosController.MODULE, "REJECT_SALE_APPROVAL", "Failed to reject " + numberOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/sale-approvals/" + id;
    }

    /** "U-WH-000012 · CLR-6 3210 x 2250: 27,000 to 24,000 (11.11% off)" or "credit 300,000 RWF for ACME (over by 120,000)". */
    private String describe(SaleApproval a) {
        if (a.isPrice()) {
            return a.getSubject() + ": " + num.money(a.getListPrice()) + " to " + num.money(a.getRequestedPrice()) + " ("
                    + num.m2(a.getDiscountPercent()) + "% off, limit " + num.m2(a.getLimitPercent()) + "%)";
        }
        return "credit " + num.money(a.getCreditAmount()) + " RWF for " + a.getSubject() + " (over the limit by "
                + num.money(a.getOverLimit()) + " RWF)";
    }

    private String numberOf(UUID id) {
        try {
            return approvalService.findDetailed(id).getNumber();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }
}
