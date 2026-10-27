package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.PriceChange;
import com.ntaganira.heritier.iWarehouse.entity.PriceChangeLine;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.PriceChangeStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.PriceChangeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : PriceChangeController.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Price changes (SDP 5): the list, a change with each price from and to, approval within the Delegation of
 *               Authority (the prices apply), rejection and withdrawal with a reason, its History. PAGE_PRICE_LISTS +
 *               PERM_VIEW_PRICE_LIST; approve and reject PERM_APPROVE_PRICE_CHANGE; withdraw PERM_MANAGE_PRICE_LIST.
 * </pre>
 */
@Controller
@RequestMapping("/price-lists/changes")
public class PriceChangeController {

    private final PriceChangeService changeService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public PriceChangeController(PriceChangeService changeService, DataChangeService dataChangeService, ActivityLogService activityLogService,
                                 Messages messages) {
        this.changeService = changeService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_VIEW_PRICE_LIST')")
    public String list(@RequestParam(required = false) String status, @RequestParam(required = false) String search,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("changes", changeService.findPage(status, search, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", PriceChangeStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("search", search);
        model.addAttribute("paginationQuery", QueryString.of("status", status, "search", search));
        return "price-lists/changes";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_VIEW_PRICE_LIST')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        PriceChange change = changeService.findDetailed(id);
        model.addAttribute("change", change);
        List<PriceChangeLine> lines = changeService.lines(id);
        model.addAttribute("lines", lines);
        // How much each price moves, in % of the old one (a price added or cleared has none)
        Map<Integer, BigDecimal> percents = new HashMap<>();
        lines.stream().filter(l -> l.getOldPrice() != null && l.getNewPrice() != null).forEach(l -> percents.put(l.getLineNo(),
                l.getNewPrice().subtract(l.getOldPrice()).multiply(BigDecimal.valueOf(100)).divide(l.getOldPrice(), 1, RoundingMode.HALF_UP)));
        model.addAttribute("percents", percents);
        model.addAttribute("mine", PriceChangeService.isRequester(change));
        model.addAttribute("authority", change.isPending() ? changeService.authorityOf(change) : null);
        model.addAttribute("history", dataChangeService.history("PriceChange", id.toString(), Paging.page(page), Paging.SIZE));
        return "price-lists/change";
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_APPROVE_PRICE_CHANGE')")
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            PriceChange change = changeService.approve(id, note);
            activityLogService.record(PriceListController.MODULE, "APPROVE_PRICE_CHANGE", "Approved price change " + change.getNumber() + " of "
                    + change.getRequestedBy() + " on " + change.getPriceList().getName() + ": " + change.getReason(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("priceChange.approvedMsg", change.getNumber(), change.getPriceList().getName()));
        } catch (BusinessException e) {
            failed(id, "APPROVE_PRICE_CHANGE", "approve", e, redirect);
        }
        return "redirect:/price-lists/changes/" + id;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_APPROVE_PRICE_CHANGE')")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return withReason(id, reason, redirect, true);
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String withdraw(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return withReason(id, reason, redirect, false);
    }

    private String withReason(UUID id, String reason, RedirectAttributes redirect, boolean reject) {
        String action = reject ? "REJECT_PRICE_CHANGE" : "CANCEL_PRICE_CHANGE";
        if (!StringUtils.hasText(reason) || reason.trim().length() > 255) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/price-lists/changes/" + id;
        }
        try {
            PriceChange change = AuditContext.withReason(reason.trim(),
                    () -> reject ? changeService.reject(id, reason) : changeService.withdraw(id, reason));
            activityLogService.record(PriceListController.MODULE, action, (reject ? "Rejected" : "Withdrew") + " price change "
                    + change.getNumber() + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(reject ? "priceChange.rejectedMsg" : "priceChange.withdrawnMsg", change.getNumber()));
        } catch (BusinessException e) {
            failed(id, action, reject ? "reject" : "withdraw", e, redirect);
        }
        return "redirect:/price-lists/changes/" + id;
    }

    private void failed(UUID id, String action, String verb, BusinessException e, RedirectAttributes redirect) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        String number;
        try {
            number = changeService.findDetailed(id).getNumber();
        } catch (RuntimeException ex) {
            number = id.toString();
        }
        activityLogService.record(PriceListController.MODULE, action, "Failed to " + verb + " price change " + number + ": " + error,
                ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }
}
