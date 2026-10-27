package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.Territory;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.TerritoryService;
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
 * - File      : TerritoryController.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Sales territories and their Regional Sales Managers (BP 6, Res. 7): the list, add, change, deactivate
 *               and activate again. PAGE_TERRITORIES; changes PERM_MANAGE_TERRITORIES.
 * </pre>
 */
@Controller
@RequestMapping("/territories")
public class TerritoryController {

    static final String MODULE = "Territories";
    private static final String MANAGE = "hasAuthority('PAGE_TERRITORIES') and hasAuthority('PERM_MANAGE_TERRITORIES')";

    private final TerritoryService territoryService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public TerritoryController(TerritoryService territoryService, ActivityLogService activityLogService, Messages messages) {
        this.territoryService = territoryService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_TERRITORIES')")
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("territories", Paging.of(territoryService.all(), Paging.page(page)));
        return "territories/list";
    }

    @GetMapping("/new")
    @PreAuthorize(MANAGE)
    public String createForm(Model model) {
        return form(model, null, new TerritoryService.Form(null, null, null), null, null);
    }

    @PostMapping("/new")
    @PreAuthorize(MANAGE)
    public String create(@RequestParam(required = false) String code, @RequestParam(required = false) String name,
                         @RequestParam(required = false) Long managerUserId, Model model, RedirectAttributes redirect) {
        TerritoryService.Form f = new TerritoryService.Form(code, name, managerUserId);
        try {
            Territory t = territoryService.create(f);
            log("CREATE_TERRITORY", "Added territory " + t.getCode() + " " + t.getName()
                    + (t.getManagerName() == null ? "" : ", managed by " + t.getManagerName()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("territory.created", t.getCode()));
            return "redirect:/territories";
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            log("CREATE_TERRITORY", "Failed to add a territory: " + error, ActivityStatus.FAILED);
            return form(model, null, f, e.getField(), error);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize(MANAGE)
    public String editForm(@PathVariable UUID id, Model model) {
        Territory t = territoryService.find(id);
        return form(model, t, new TerritoryService.Form(t.getCode(), t.getName(), t.getManagerUserId()), null, null);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize(MANAGE)
    public String update(@PathVariable UUID id, @RequestParam(required = false) String code, @RequestParam(required = false) String name,
                         @RequestParam(required = false) Long managerUserId, Model model, RedirectAttributes redirect) {
        TerritoryService.Form f = new TerritoryService.Form(code, name, managerUserId);
        try {
            Territory t = territoryService.update(id, f);
            log("UPDATE_TERRITORY", "Updated territory " + t.getCode() + " " + t.getName()
                    + (t.getManagerName() == null ? "" : ", managed by " + t.getManagerName()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("territory.updated", t.getCode()));
            return "redirect:/territories";
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            log("UPDATE_TERRITORY", "Failed to update territory " + id + ": " + error, ActivityStatus.FAILED);
            return form(model, territoryService.find(id), f, e.getField(), error);
        }
    }

    @PostMapping("/{id}/toggle")
    @PreAuthorize(MANAGE)
    public String toggle(@PathVariable UUID id, RedirectAttributes redirect) {
        Territory current = territoryService.find(id);
        Territory t = territoryService.setEnabled(id, !current.isEnabled());
        log(t.isEnabled() ? "ACTIVATE_TERRITORY" : "DEACTIVATE_TERRITORY", (t.isEnabled() ? "Activated" : "Deactivated")
                + " territory " + t.getCode(), ActivityStatus.SUCCESS);
        redirect.addFlashAttribute("flashSuccess", messages.get(t.isEnabled() ? "territory.activated" : "territory.deactivated", t.getCode()));
        return "redirect:/territories";
    }

    private String form(Model model, Territory current, TerritoryService.Form f, String errorField, String error) {
        model.addAttribute("territory", current);
        model.addAttribute("form", f);
        model.addAttribute("managers", territoryService.managers());
        model.addAttribute("errorField", errorField);
        if (error != null) {
            model.addAttribute("flashError", error);
        }
        return "territories/form";
    }

    private void log(String action, String text, ActivityStatus status) {
        activityLogService.record(MODULE, action, text, status);
    }
}
