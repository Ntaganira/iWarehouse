package com.ntaganira.heritier.iWarehouse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.SyncConflict;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.SyncConflictReason;
import com.ntaganira.heritier.iWarehouse.enums.SyncConflictStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.SyncConflictService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : SyncConflictController.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Mobile sales in conflict (SYNC-05): list, the sale as the phone sent it, and the supervisor's review with a
 *               note of what was done. PAGE_SYNC_CONFLICTS + PERM_VIEW_SYNC_CONFLICTS; reviewing needs
 *               PERM_REVIEW_SYNC_CONFLICT.
 * </pre>
 */
@Controller
@RequestMapping("/sync-conflicts")
public class SyncConflictController {

    static final String MODULE = "Mobile POS";
    private static final int NOTE_MAX = 255;

    private final SyncConflictService conflictService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final ObjectMapper mapper;
    private final Messages messages;

    public SyncConflictController(SyncConflictService conflictService, DataChangeService dataChangeService,
                                  ActivityLogService activityLogService, ObjectMapper mapper, Messages messages) {
        this.conflictService = conflictService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.mapper = mapper;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_SYNC_CONFLICTS') and hasAuthority('PERM_VIEW_SYNC_CONFLICTS')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) SyncConflictStatus status,
                       @RequestParam(required = false) SyncConflictReason reason, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("conflicts", conflictService.findPage(search, status, reason, Paging.page(page), Paging.SIZE));
        model.addAttribute("open", conflictService.openCount());
        model.addAttribute("statuses", SyncConflictStatus.values());
        model.addAttribute("reasons", SyncConflictReason.values());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("reason", reason);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status, "reason", reason));
        return "sync-conflicts/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_SYNC_CONFLICTS') and hasAuthority('PERM_VIEW_SYNC_CONFLICTS')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "details") String tab, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        String open = List.of("details", "history").contains(tab) ? tab : "details";
        SyncConflict conflict = conflictService.findDetailed(id);
        model.addAttribute("conflict", conflict);
        model.addAttribute("sent", conflictService.sent(conflict).orElse(null));
        model.addAttribute("payload", pretty(conflict.getPayload()));
        model.addAttribute("history", dataChangeService.history("SyncConflict", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "sync-conflicts/view";
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('PAGE_SYNC_CONFLICTS') and hasAuthority('PERM_REVIEW_SYNC_CONFLICT')")
    public String review(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > NOTE_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("sync.note.required"));
            return "redirect:/sync-conflicts/" + id;
        }
        try {
            SyncConflict c = AuditContext.withReason(reason.trim(), () -> conflictService.review(id, reason));
            activityLogService.record(MODULE, "REVIEW_SYNC_CONFLICT", "Reviewed mobile sale " + c.getNumber() + " (" + c.getReason() + "): "
                    + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("sync.reviewed", c.getNumber() == null ? "—" : c.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "REVIEW_SYNC_CONFLICT", "Failed to review a mobile sale in conflict: " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/sync-conflicts/" + id;
    }

    /** The payload indented for reading. */
    private String pretty(String json) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(json));
        } catch (Exception e) {
            return json;
        }
    }
}
