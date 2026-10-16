package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.CostCentreDto;
import com.ntaganira.heritier.iWarehouse.entity.CostCentre;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.CostCentreService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : CostCentreController.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Cost centres (F-15 A, T.05): the list, the form (code, name, manager) and deactivation. PAGE_COST_CENTRES;
 *               changes need PERM_MANAGE_COST_CENTRES.
 * </pre>
 */
@Controller
@RequestMapping("/cost-centres")
public class CostCentreController {

    static final String MODULE = "Accounting";
    private static final String MANAGE = "hasAuthority('PAGE_COST_CENTRES') and hasAuthority('PERM_MANAGE_COST_CENTRES')";

    private final CostCentreService service;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public CostCentreController(CostCentreService service, ActivityLogService activityLogService, Messages messages) {
        this.service = service;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_COST_CENTRES')")
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("centres", Paging.of(service.all(), Paging.page(page)));
        return "cost-centres/list";
    }

    @GetMapping("/new")
    @PreAuthorize(MANAGE)
    public String create(Model model) {
        return form(model, new CostCentreDto(), null);
    }

    @PostMapping("/new")
    @PreAuthorize(MANAGE)
    public String create(@Valid @ModelAttribute("centreDto") CostCentreDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        return save(null, dto, result, model, redirect);
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize(MANAGE)
    public String edit(@PathVariable UUID id, Model model) {
        CostCentre c = service.find(id);
        return form(model, service.formOf(c), c);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize(MANAGE)
    public String edit(@PathVariable UUID id, @Valid @ModelAttribute("centreDto") CostCentreDto dto, BindingResult result, Model model,
                       RedirectAttributes redirect) {
        return save(id, dto, result, model, redirect);
    }

    private String save(UUID id, CostCentreDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        CostCentre existing = id == null ? null : service.find(id);
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto, existing);
        }
        try {
            CostCentre c = service.save(id, dto);
            activityLogService.record(MODULE, id == null ? "CREATE_COST_CENTRE" : "UPDATE_COST_CENTRE", (id == null ? "Added" : "Updated")
                    + " cost centre " + c.getCode() + " " + c.getName() + (c.getManagerName() == null ? "" : ", managed by " + c.getManagerName())
                    + (c.isEnabled() ? "" : " (inactive)"), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("costCentre.saved", c.getCode()));
            return "redirect:/cost-centres";
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, id == null ? "CREATE_COST_CENTRE" : "UPDATE_COST_CENTRE", "Failed to save a cost centre: " + error,
                    ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
                model.addAttribute("formErrors", result.getFieldErrors());
            } else {
                model.addAttribute("flashError", error);
            }
            return form(model, dto, existing);
        }
    }

    private String form(Model model, CostCentreDto dto, CostCentre current) {
        model.addAttribute("centreDto", dto);
        model.addAttribute("centre", current);
        model.addAttribute("users", service.users());
        return "cost-centres/form";
    }
}
