package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.AuthorityDto;
import com.ntaganira.heritier.iWarehouse.dto.SettingsDto;
import com.ntaganira.heritier.iWarehouse.entity.NumberSequence;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityDecision;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.enums.EbmMode;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

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
    private static final Set<String> TABS = Set.of("general", "authority", "tax", "numbering", "history");
    /** Entity types whose changes the History tab lists. */
    private static final List<String> AUDITED_TYPES = List.of("Setting", "TaxCategory", "NumberSequence", "AuthorityLimit");

    private final SettingService settingService;
    private final TaxCategoryService taxCategoryService;
    private final DocumentNumberService documentNumberService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final CompanyLogoService companyLogoService;
    private final DelegationOfAuthority authority;

    public SettingsController(SettingService settingService, TaxCategoryService taxCategoryService,
                              DocumentNumberService documentNumberService, DataChangeService dataChangeService,
                              ActivityLogService activityLogService, Messages messages,
                              CompanyLogoService companyLogoService, DelegationOfAuthority authority) {
        this.authority = authority;
        this.settingService = settingService;
        this.taxCategoryService = taxCategoryService;
        this.documentNumberService = documentNumberService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.companyLogoService = companyLogoService;
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
        model.addAttribute("companyLogoUrl", companyLogoService.url());
        model.addAttribute("authorityTable", authority.table());
        model.addAttribute("authorityDecisions", AuthorityDecision.values());
        model.addAttribute("authorityLevels", AuthorityLevel.values());
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

    // ---------------------------------------------------------------- the company's logo (on documents)

    @PostMapping("/logo")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String uploadLogo(@RequestParam(required = false) MultipartFile logo, RedirectAttributes redirect) {
        String name = logo == null ? null : logo.getOriginalFilename();
        try {
            String key = companyLogoService.upload(logo == null ? new byte[0] : logo.getBytes());
            activityLogService.record(MODULE, "UPDATE_SETTINGS", "Changed the company logo (" + name + ", kept as " + key + ")",
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("settings.logo.saved"));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_SETTINGS", "Failed to change the company logo (" + name + "): " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        } catch (IOException e) {
            redirect.addFlashAttribute("flashError", messages.get("settings.logo.unreadable"));
        }
        return "redirect:/settings";
    }

    @PostMapping("/logo/remove")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String removeLogo(RedirectAttributes redirect) {
        if (companyLogoService.remove()) {
            activityLogService.record(MODULE, "UPDATE_SETTINGS", "Removed the company logo", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("settings.logo.removed"));
        }
        return "redirect:/settings";
    }

    /** The logo itself, for the documents printed from the browser (receipts, quotations): anyone who prints them. */
    @GetMapping("/logo")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> logo() {
        return companyLogoService.png()
                .map(png -> ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400") // its address changes with the logo
                        .contentType(MediaType.IMAGE_PNG)
                        .body(png))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ---------------------------------------------------------------- the Delegation of Authority (Annex E)

    @GetMapping("/authority")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_MANAGE_AUTHORITY')")
    public String authorityForm(Model model) {
        model.addAttribute("authorityDto", authority.form());
        model.addAttribute("editableLevels", DelegationOfAuthority.EDITABLE);
        return "settings/authority-form";
    }

    @PostMapping("/authority")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_MANAGE_AUTHORITY')")
    public String updateAuthority(@ModelAttribute("authorityDto") AuthorityDto dto, BindingResult result, Model model,
                                  RedirectAttributes redirect) {
        try {
            List<String> changes = authority.update(dto);
            if (changes.isEmpty()) {
                redirect.addFlashAttribute("flashWarning", messages.get("settings.noChanges"));
            } else {
                activityLogService.record(MODULE, "UPDATE_AUTHORITY", "Changed the Delegation of Authority: " + String.join("; ", changes),
                        ActivityStatus.SUCCESS);
                redirect.addFlashAttribute("flashSuccess", messages.get("authority.updated", changes.size()));
            }
            return "redirect:/settings?tab=authority";
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_AUTHORITY", "Failed to change the Delegation of Authority: " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
                model.addAttribute("formErrors", result.getFieldErrors());
            } else {
                model.addAttribute("flashError", error);
            }
            model.addAttribute("editableLevels", DelegationOfAuthority.EDITABLE);
            return "settings/authority-form";
        }
    }

    @GetMapping("/edit")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String editForm(Model model) {
        model.addAttribute("settingsDto", settingService.load());
        model.addAttribute("rateSources", RateSource.values());
        model.addAttribute("ebmModes", EbmMode.values());
        model.addAttribute("countTypes", com.ntaganira.heritier.iWarehouse.enums.StockCountType.values());
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
        model.addAttribute("countTypes", com.ntaganira.heritier.iWarehouse.enums.StockCountType.values());
        return "settings/form";
    }
}
