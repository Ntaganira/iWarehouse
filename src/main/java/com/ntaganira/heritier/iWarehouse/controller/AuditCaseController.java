package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.AuditCase;
import com.ntaganira.heritier.iWarehouse.entity.AuditCaseLine;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AuditCaseStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.AuditCaseService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : AuditCaseController.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : /audit-cases (FLT-10, FLT-11): the units of trips back with differences. The supervisor settles each unit
 *               (found on a rack, written off, an unexpected one explained) and may escalate the case to the owner, who alone
 *               charges a missing unit to the driver (CHARGE_DRIVER). PAGE_AUDIT_CASES + VIEW_AUDIT_CASES to open; settling
 *               needs MANAGE_AUDIT_CASE. A write-off, a charge, an explanation and an escalation carry a reason.
 * </pre>
 */
@Controller
@RequestMapping("/audit-cases")
public class AuditCaseController {

    static final String MODULE = "Fleet";
    private static final int REASON_MAX = 255;

    private final AuditCaseService caseService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final NumberFormats num;
    private final Messages messages;

    public AuditCaseController(AuditCaseService caseService, DataChangeService dataChangeService, ActivityLogService activityLogService,
                               NumberFormats num, Messages messages) {
        this.caseService = caseService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.num = num;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_AUDIT_CASES') and hasAuthority('PERM_VIEW_AUDIT_CASES')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) AuditCaseStatus status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("cases", caseService.findPage(search, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("open", caseService.openCount());
        model.addAttribute("statuses", AuditCaseStatus.values());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "audit-cases/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_AUDIT_CASES') and hasAuthority('PERM_VIEW_AUDIT_CASES')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "details") String tab, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        String open = List.of("details", "history").contains(tab) ? tab : "details";
        AuditCase auditCase = caseService.findDetailed(id);
        model.addAttribute("auditCase", auditCase);
        model.addAttribute("lines", AuditCaseService.sortedLines(auditCase));
        model.addAttribute("units", caseService.units(auditCase));
        model.addAttribute("history", dataChangeService.historyWithChildren("AuditCase", id.toString(), "AuditCaseLine", "auditCase",
                Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "audit-cases/view";
    }

    @PostMapping("/{id}/lines/{lineId}/found")
    @PreAuthorize("hasAuthority('PAGE_AUDIT_CASES') and hasAuthority('PERM_MANAGE_AUDIT_CASE')")
    public String found(@PathVariable UUID id, @PathVariable UUID lineId, @RequestParam(required = false) String place,
                        @RequestParam(required = false) String note, RedirectAttributes redirect) {
        return settle(id, "FOUND_UNIT", () -> caseService.found(id, lineId, place, note), "auditCase.found", redirect,
                l -> "Found " + l.getUnitCode() + " (" + l.getNote() + ")");
    }

    @PostMapping("/{id}/lines/{lineId}/write-off")
    @PreAuthorize("hasAuthority('PAGE_AUDIT_CASES') and hasAuthority('PERM_MANAGE_AUDIT_CASE')")
    public String writeOff(@PathVariable UUID id, @PathVariable UUID lineId, @RequestParam(required = false) String reason,
                           RedirectAttributes redirect) {
        if (tooLong(reason, id, redirect)) {
            return back(id);
        }
        return settle(id, "WRITE_OFF_UNIT", () -> AuditContext.withReason(trim(reason), () -> caseService.writeOff(id, lineId, reason)),
                "auditCase.writtenOff", redirect, l -> "Wrote off " + l.getUnitCode() + " (" + num.money(l.getUnitValue()) + " RWF): " + l.getNote());
    }

    /** FLT-11: the owner's approval charges a missing unit to the driver. */
    @PostMapping("/{id}/lines/{lineId}/charge")
    @PreAuthorize("hasAuthority('PAGE_AUDIT_CASES') and hasAuthority('PERM_CHARGE_DRIVER')")
    public String charge(@PathVariable UUID id, @PathVariable UUID lineId, @RequestParam(required = false) String reason,
                         RedirectAttributes redirect) {
        if (tooLong(reason, id, redirect)) {
            return back(id);
        }
        return settle(id, "APPROVE_DRIVER_CHARGE", () -> AuditContext.withReason(trim(reason), () -> caseService.charge(id, lineId, reason)),
                "auditCase.charged", redirect, l -> "Charged " + l.getUnitCode() + " (" + num.money(l.getUnitValue()) + " RWF) to the driver: " + l.getNote());
    }

    @PostMapping("/{id}/lines/{lineId}/explain")
    @PreAuthorize("hasAuthority('PAGE_AUDIT_CASES') and hasAuthority('PERM_MANAGE_AUDIT_CASE')")
    public String explain(@PathVariable UUID id, @PathVariable UUID lineId, @RequestParam(required = false) String reason,
                          RedirectAttributes redirect) {
        if (tooLong(reason, id, redirect)) {
            return back(id);
        }
        return settle(id, "EXPLAIN_UNIT", () -> AuditContext.withReason(trim(reason), () -> caseService.explain(id, lineId, reason)),
                "auditCase.explained", redirect, l -> "Explained " + l.getUnitCode() + ": " + l.getNote());
    }

    @PostMapping("/{id}/escalate")
    @PreAuthorize("hasAuthority('PAGE_AUDIT_CASES') and hasAuthority('PERM_MANAGE_AUDIT_CASE')")
    public String escalate(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (tooLong(reason, id, redirect)) {
            return back(id);
        }
        try {
            AuditCase c = AuditContext.withReason(trim(reason), () -> caseService.escalate(id, reason));
            activityLogService.record(MODULE, "ESCALATE_AUDIT_CASE", "Escalated audit case " + c.getNumber() + " of trip "
                    + c.getTrip().getNumber() + " to the owner: " + c.getEscalationNote(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("auditCase.escalated", c.getNumber()));
        } catch (BusinessException e) {
            fail(id, "ESCALATE_AUDIT_CASE", e, redirect);
        }
        return back(id);
    }

    private String settle(UUID id, String action, Supplier<AuditCaseLine> work, String doneKey, RedirectAttributes redirect,
                          java.util.function.Function<AuditCaseLine, String> describe) {
        try {
            AuditCaseLine line = work.get();
            AuditCase c = line.getAuditCase();
            activityLogService.record(MODULE, action, describe.apply(line) + ", audit case " + c.getNumber(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(doneKey, line.getUnitCode())
                    + (c.isResolved() ? " " + messages.get("auditCase.resolved", c.getNumber()) : ""));
        } catch (BusinessException e) {
            fail(id, action, e, redirect);
        }
        return back(id);
    }

    private boolean tooLong(String reason, UUID id, RedirectAttributes redirect) {
        if (reason != null && reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("breakage.text.tooLong", REASON_MAX));
            return true;
        }
        return false;
    }

    private void fail(UUID id, String action, BusinessException e, RedirectAttributes redirect) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        String number;
        try {
            number = caseService.findDetailed(id).getNumber();
        } catch (RuntimeException ex) {
            number = id.toString();
        }
        activityLogService.record(MODULE, action, "Refused on audit case " + number + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }

    private static String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String back(UUID id) {
        return "redirect:/audit-cases/" + id;
    }
}
