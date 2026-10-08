package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.TaxCategoryDto;
import com.ntaganira.heritier.iWarehouse.entity.TaxCategory;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.TaxCategoryService;
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
 * - File      : TaxCategoryController.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Tax categories on the Settings screen (TAX-01, ADM-03): add, edit, activate and
 *               deactivate (no delete). Listed on /settings?tab=tax. PAGE_SETTINGS + PERM_EDIT_SETTINGS.
 * </pre>
 */
@Controller
@RequestMapping("/settings/tax-categories")
public class TaxCategoryController {

    private static final String BACK = "redirect:/settings?tab=tax";

    private final TaxCategoryService taxCategoryService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public TaxCategoryController(TaxCategoryService taxCategoryService, DataChangeService dataChangeService,
                                 ActivityLogService activityLogService, Messages messages) {
        this.taxCategoryService = taxCategoryService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String createForm(Model model) {
        return form(model, new TaxCategoryDto());
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String create(@Valid @ModelAttribute("taxCategoryDto") TaxCategoryDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            TaxCategory category = taxCategoryService.create(dto);
            activityLogService.record(SettingsController.MODULE, "CREATE_TAX_CATEGORY",
                    "Created tax category " + describe(category), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("tax.created", category.getCode()));
            return BACK;
        } catch (BusinessException e) {
            activityLogService.record(SettingsController.MODULE, "CREATE_TAX_CATEGORY", "Failed to create tax category "
                    + dto.getCode() + ": " + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String editForm(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        TaxCategory category = taxCategoryService.findById(id);
        TaxCategoryDto dto = new TaxCategoryDto();
        dto.setId(id);
        dto.setCode(category.getCode());
        dto.setName(category.getName());
        dto.setRate(category.getRate());
        dto.setEbmCode(category.getEbmCode());
        dto.setDescription(category.getDescription());
        dto.setDefaultCategory(category.isDefaultCategory());
        return form(model, dto, page);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("taxCategoryDto") TaxCategoryDto dto,
                         BindingResult result, Model model, RedirectAttributes redirect) {
        dto.setId(id);
        dto.setCode(taxCategoryService.findById(id).getCode()); // fixed after creation
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            TaxCategory category = taxCategoryService.update(id, dto);
            activityLogService.record(SettingsController.MODULE, "UPDATE_TAX_CATEGORY",
                    "Updated tax category " + describe(category), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("tax.updated", category.getCode()));
            return BACK;
        } catch (BusinessException e) {
            activityLogService.record(SettingsController.MODULE, "UPDATE_TAX_CATEGORY", "Failed to update tax category "
                    + dto.getCode() + ": " + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String disable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, false, redirect);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String enable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, true, redirect);
    }

    private String setEnabled(UUID id, boolean enabled, RedirectAttributes redirect) {
        String action = enabled ? "ENABLE_TAX_CATEGORY" : "DISABLE_TAX_CATEGORY";
        try {
            TaxCategory category = taxCategoryService.setEnabled(id, enabled);
            activityLogService.record(SettingsController.MODULE, action,
                    (enabled ? "Activated" : "Deactivated") + " tax category " + category.getCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess",
                    messages.get(enabled ? "tax.enabledMsg" : "tax.disabledMsg", category.getCode()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(SettingsController.MODULE, action,
                    "Failed to change status of tax category " + id + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return BACK;
    }

    private static String describe(TaxCategory category) {
        return category.getCode() + " (" + category.getRate().stripTrailingZeros().toPlainString() + "%, EBM "
                + category.getEbmCode() + (category.isDefaultCategory() ? ", default" : "") + ")";
    }

    private String form(Model model, TaxCategoryDto dto) {
        return form(model, dto, 0);
    }

    private String form(Model model, TaxCategoryDto dto, int historyPage) {
        model.addAttribute("taxCategoryDto", dto);
        if (dto.getId() != null) {
            TaxCategory category = taxCategoryService.findById(dto.getId());
            model.addAttribute("category", category);
            model.addAttribute("history", dataChangeService.history("TaxCategory", dto.getId().toString(), Paging.page(historyPage), Paging.SIZE));
        }
        return "settings/tax-form";
    }

    private String invalid(Model model, TaxCategoryDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    /** A business rule refused the form: show it next to its field, or as a toast. */
    private String rejected(Model model, TaxCategoryDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }
}
