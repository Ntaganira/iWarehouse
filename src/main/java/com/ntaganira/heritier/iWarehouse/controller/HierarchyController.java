package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AdministrativeTier;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.HierarchyService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : HierarchyController.java
 * - Date      : 2026. 11. 01.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The Rwanda administrative hierarchy dashboard: cascading filters, rename, visibility toggle and
 *               guarded delete. PAGE_RWANDA_HIERARCHY + PERM_VIEW_RWANDA_HIERARCHY to read;
 *               PERM_MANAGE_RWANDA_HIERARCHY to change.
 * </pre>
 */
@Controller
@RequestMapping("/admin/hierarchy")
public class HierarchyController {

    static final String MODULE = HierarchyService.MODULE;

    private final HierarchyService hierarchyService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public HierarchyController(HierarchyService hierarchyService, ActivityLogService activityLogService, Messages messages) {
        this.hierarchyService = hierarchyService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_RWANDA_HIERARCHY') and hasAuthority('PERM_VIEW_RWANDA_HIERARCHY')")
    public String dashboard(@RequestParam(required = false) AdministrativeTier tier,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false) String search,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) UUID province,
                            @RequestParam(required = false) UUID district,
                            @RequestParam(required = false) UUID sector,
                            @RequestParam(required = false) UUID cell,
                            @RequestParam(defaultValue = "0") int page,
                            Model model) {
        String text = StringUtils.hasText(q) ? q : search;
        model.addAttribute("dashboard", hierarchyService.dashboard(
                new HierarchyService.Filter(tier, text, status, province, district, sector, cell, page)));
        model.addAttribute("tiers", AdministrativeTier.values());
        model.addAttribute("statuses", HierarchyService.STATUSES);
        return "admin/hierarchy";
    }

    @PostMapping("/rename")
    @PreAuthorize("hasAuthority('PAGE_RWANDA_HIERARCHY') and hasAuthority('PERM_MANAGE_RWANDA_HIERARCHY')")
    public String rename(@RequestParam AdministrativeTier tier, @RequestParam UUID id,
                         @RequestParam(required = false) String name,
                         @RequestParam(required = false) String returnQuery,
                         RedirectAttributes redirect) {
        try {
            HierarchyService.RenameResult result = hierarchyService.rename(tier, id, name);
            activityLogService.record(MODULE, "UPDATE_HIERARCHY_NAME",
                    "Renamed " + tier + " " + result.code() + " from \"" + result.before() + "\" to \"" + result.after() + "\"",
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess",
                    messages.get("hierarchy.renamed", result.code(), result.after()));
        } catch (BusinessException e) {
            failed("UPDATE_HIERARCHY_NAME", "rename " + tier + " " + id, e, redirect);
        }
        return back(returnQuery);
    }

    @PostMapping("/toggle")
    @PreAuthorize("hasAuthority('PAGE_RWANDA_HIERARCHY') and hasAuthority('PERM_MANAGE_RWANDA_HIERARCHY')")
    public String toggle(@RequestParam AdministrativeTier tier, @RequestParam UUID id,
                         @RequestParam(required = false) String returnQuery,
                         RedirectAttributes redirect) {
        try {
            HierarchyService.ToggleResult result = hierarchyService.toggle(tier, id);
            activityLogService.record(MODULE, "UPDATE_HIERARCHY_VISIBILITY",
                    (result.enabled() ? "Enabled " : "Disabled ") + tier + " " + result.code() + " (" + result.name() + ")",
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(
                    result.enabled() ? "hierarchy.enabled" : "hierarchy.disabled", result.code(), result.name()));
        } catch (BusinessException e) {
            failed("UPDATE_HIERARCHY_VISIBILITY", "change the visibility of " + tier + " " + id, e, redirect);
        }
        return back(returnQuery);
    }

    @PostMapping("/delete")
    @PreAuthorize("hasAuthority('PAGE_RWANDA_HIERARCHY') and hasAuthority('PERM_MANAGE_RWANDA_HIERARCHY')")
    public String delete(@RequestParam AdministrativeTier tier, @RequestParam UUID id,
                         @RequestParam(required = false) String confirmCode,
                         @RequestParam(required = false) String returnQuery,
                         RedirectAttributes redirect) {
        try {
            HierarchyService.DeleteResult result = hierarchyService.delete(tier, id, confirmCode);
            activityLogService.record(MODULE, "DELETE_HIERARCHY",
                    "Deleted " + tier + " " + result.code() + " (" + result.name() + ") and "
                            + result.descendants() + " lower record(s)", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess",
                    messages.get("hierarchy.deleted", result.code(), result.total()));
        } catch (BusinessException e) {
            failed("DELETE_HIERARCHY", "delete " + tier + " " + id, e, redirect);
        }
        return back(returnQuery);
    }

    private void failed(String action, String what, BusinessException e, RedirectAttributes redirect) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        activityLogService.record(MODULE, action, "Failed to " + what + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }

    /** Only our dashboard, whatever query was on the page; a query can never turn this into another host. */
    private String back(String returnQuery) {
        if (!StringUtils.hasText(returnQuery)) {
            return "redirect:/admin/hierarchy";
        }
        String query = returnQuery.trim();
        if (query.startsWith("?")) {
            query = query.substring(1);
        }
        if (query.isEmpty() || query.indexOf('\r') >= 0 || query.indexOf('\n') >= 0) {
            return "redirect:/admin/hierarchy";
        }
        return "redirect:/admin/hierarchy?" + query;
    }
}
