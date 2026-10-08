package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.dto.NumberSequenceDto;
import com.ntaganira.heritier.iWarehouse.entity.NumberSequence;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.ResetPolicy;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.DocumentNumberService;
import com.ntaganira.heritier.iWarehouse.service.SettingService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : NumberSequenceController.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Document numbering on the Settings screen (MD-07): add a sequence for a document type
 *               and branch, edit its format and next number. Listed on /settings?tab=numbering.
 *               PAGE_SETTINGS + PERM_EDIT_SETTINGS. Sequences are never deleted.
 * </pre>
 */
@Controller
@RequestMapping("/settings/numbering")
public class NumberSequenceController {

    private static final String BACK = "redirect:/settings?tab=numbering";

    private final DocumentNumberService documentNumberService;
    private final SettingService settingService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final Clock clock;

    public NumberSequenceController(DocumentNumberService documentNumberService, SettingService settingService,
                                    DataChangeService dataChangeService, ActivityLogService activityLogService,
                                    Messages messages, Clock clock) {
        this.documentNumberService = documentNumberService;
        this.settingService = settingService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.clock = clock;
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String createForm(Model model) {
        NumberSequenceDto dto = new NumberSequenceDto();
        dto.setBranchCode(settingService.branchCode());
        return form(model, dto);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String create(@Valid @ModelAttribute("sequenceDto") NumberSequenceDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            NumberSequence sequence = documentNumberService.create(dto);
            activityLogService.record(SettingsController.MODULE, "CREATE_NUMBERING",
                    "Created numbering " + label(sequence) + ", first number " + documentNumberService.preview(sequence),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("numbering.created", label(sequence)));
            return BACK;
        } catch (BusinessException e) {
            activityLogService.record(SettingsController.MODULE, "CREATE_NUMBERING", "Failed to create numbering "
                    + dto.getDocType() + "/" + dto.getBranchCode() + ": " + messages.get(e.getMessageKey(), e.getArgs()),
                    ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String editForm(@PathVariable UUID id, Model model) {
        NumberSequence sequence = documentNumberService.findById(id);
        NumberSequenceDto dto = new NumberSequenceDto();
        dto.setId(id);
        dto.setDocType(sequence.getDocType());
        dto.setBranchCode(sequence.getBranchCode());
        dto.setPrefix(sequence.getPrefix());
        dto.setResetPolicy(sequence.getResetPolicy());
        dto.setPadding(sequence.getPadding());
        dto.setNextValue(documentNumberService.nextValue(sequence));
        return form(model, dto);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_SETTINGS') and hasAuthority('PERM_EDIT_SETTINGS')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("sequenceDto") NumberSequenceDto dto,
                         BindingResult result, Model model, RedirectAttributes redirect) {
        NumberSequence current = documentNumberService.findById(id);
        dto.setId(id);
        dto.setDocType(current.getDocType());        // fixed after creation
        dto.setBranchCode(current.getBranchCode());
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            DocumentNumberService.SequenceUpdate update = documentNumberService.update(id, dto);
            NumberSequence sequence = update.sequence();
            String description = "Updated numbering " + label(sequence) + ", next " + documentNumberService.preview(sequence)
                    + (update.counterChange() != null ? " (" + update.counterChange() + ")" : "");
            activityLogService.record(SettingsController.MODULE, "UPDATE_NUMBERING", description, ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("numbering.updated", label(sequence)));
            return BACK;
        } catch (BusinessException e) {
            activityLogService.record(SettingsController.MODULE, "UPDATE_NUMBERING", "Failed to update numbering "
                    + label(current) + ": " + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    private static String label(NumberSequence sequence) {
        return sequence.getDocType() + "/" + sequence.getBranchCode();
    }

    private String form(Model model, NumberSequenceDto dto) {
        model.addAttribute("sequenceDto", dto);
        model.addAttribute("docTypes", DocumentType.values());
        model.addAttribute("resetPolicies", ResetPolicy.values());
        // Today's year and month, for the live preview of the number format.
        LocalDate today = LocalDate.now(clock);
        model.addAttribute("previewYear", today.getYear());
        model.addAttribute("previewMonth", String.format("%02d", today.getMonthValue()));
        if (dto.getId() != null) {
            NumberSequence sequence = documentNumberService.findById(dto.getId());
            model.addAttribute("sequence", sequence);
            model.addAttribute("history", dataChangeService.history("NumberSequence", dto.getId().toString(), 0, 10));
        }
        return "settings/numbering-form";
    }

    private String invalid(Model model, NumberSequenceDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    /** A business rule refused the form: show it next to its field, or as a toast. */
    private String rejected(Model model, NumberSequenceDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }
}
