package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.SettingsDto;
import com.ntaganira.heritier.iWarehouse.entity.NumberSequence;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.EbmMode;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : SettingsController.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Settings screen (ADM-03): general settings, tax categories (TAX-01) and document
 *               numbering (MD-07) on tabs, plus their History. PAGE_SETTINGS + PERM_VIEW_SETTINGS
 *               open it; PERM_EDIT_SETTINGS changes anything on it.
 * </pre>
 */
@Controller
@RequestMapping("/settings")
public class SettingsController {

    static final String MODULE = "Settings";
    private static final Set<String> TABS = Set.of("general", "tax", "numbering", "history");
    /** Entity types whose changes the History tab lists. */
    private static final List<String> AUDITED_TYPES = List.of("Setting", "TaxCategory", "NumberSequence");

    private final SettingService settingService;
    private final TaxCategoryService taxCategoryService;
    private final DocumentNumberService documentNumberService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public SettingsController(SettingService settingService, TaxCategoryService taxCategoryService,
                              DocumentNumberService documentNumberService, DataChangeService dataChangeService,
                              ActivityLogService activityLogService, Messages messages) {
        this.settingService = settingService;
        this.taxCategoryService = taxCategoryService;
        this.documentNumberService = documentNumberService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_VIEW_SETTINGS')")
    public String view(@RequestParam(defaultValue = "general") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        String open = TABS.contains(tab) ? tab : "general";
        List<NumberSequence> sequences = documentNumberService.findAll();
        Map<UUID, String> previews = new HashMap<>();
        Map<UUID, Long> nextValues = new HashMap<>();
        for (NumberSequence s : sequences) {
            previews.put(s.getId(), documentNumberService.preview(s));
            nextValues.put(s.getId(), documentNumberService.nextValue(s));
        }
        model.addAttribute("settings", settingService.load());
        // The open tab shows the page asked for; the others start at their first page.
        model.addAttribute("taxCategories", Paging.of(taxCategoryService.findAll(), Paging.pageOf("tax", open, page)));
        model.addAttribute("sequences", Paging.of(sequences, Paging.pageOf("numbering", open, page)));
        model.addAttribute("previews", previews);
        model.addAttribute("nextValues", nextValues);
        if (AppUserPrincipal.currentHas("PERM_VIEW_DATA_CHANGES")) {
            model.addAttribute("history", dataChangeService.historyOfTypes(AUDITED_TYPES, Paging.pageOf("history", open, page), Paging.SIZE));
        }
        model.addAttribute("tab", open);
        return "settings/view";
    }

    @GetMapping("/edit")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String editForm(Model model) {
        model.addAttribute("settingsDto", settingService.load());
        model.addAttribute("rateSources", RateSource.values());
        model.addAttribute("ebmModes", EbmMode.values());
        return "settings/form";
    }

    @PostMapping("/edit")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String update(@Valid @ModelAttribute("settingsDto") SettingsDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, result);
        }
        try {
            List<String> changes = settingService.update(dto);
            if (changes.isEmpty()) {
                redirect.addFlashAttribute("flashWarning", messages.get("settings.noChanges"));
            } else {
                activityLogService.record(MODULE, "UPDATE_SETTINGS", "Changed settings: " + String.join("; ", changes),
                        ActivityStatus.SUCCESS);
                redirect.addFlashAttribute("flashSuccess", messages.get("settings.updated", changes.size()));
            }
            return "redirect:/settings";
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_SETTINGS", "Failed to change settings: " + error,
                    ActivityStatus.FAILED);
            if (e.getField() != null) {
                // Pass the args too: th:errors resolves the message again from its code.
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
            } else {
                model.addAttribute("flashError", error);
            }
            return invalid(model, result);
        }
    }

    private String invalid(Model model, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        model.addAttribute("rateSources", RateSource.values());
        model.addAttribute("ebmModes", EbmMode.values());
        return "settings/form";
    }
}
