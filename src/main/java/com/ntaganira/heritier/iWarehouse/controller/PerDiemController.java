package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.PerDiemRateDto;
import com.ntaganira.heritier.iWarehouse.entity.PerDiemRate;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.PerDiemService;
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
 * - File      : PerDiemController.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Per diem rates (CMP 08): the four travel categories with their rates, and a category's rate changed
 *               (PERM_MANAGE_PER_DIEM), with its history. Needs PAGE_PER_DIEM.
 * </pre>
 */
@Controller
@RequestMapping("/payroll/per-diem")
public class PerDiemController {

    private static final String MODULE = "Payroll";
    private static final String MANAGE = "hasAuthority('PAGE_PER_DIEM') and hasAuthority('PERM_MANAGE_PER_DIEM')";

    private final PerDiemService perDiem;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public PerDiemController(PerDiemService perDiem, DataChangeService dataChangeService, ActivityLogService activityLogService, Messages messages) {
        this.perDiem = perDiem;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_PER_DIEM')")
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("rates", Paging.of(perDiem.rates(), Paging.page(page)));
        return "per-diem/list";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize(MANAGE)
    public String edit(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        PerDiemRate rate = perDiem.find(id);
        return form(model, perDiem.formOf(rate), rate, page);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize(MANAGE)
    public String edit(@PathVariable UUID id, @Valid @ModelAttribute("rateDto") PerDiemRateDto dto, BindingResult result, Model model,
                       RedirectAttributes redirect) {
        PerDiemRate rate = perDiem.find(id);
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto, rate, 0);
        }
        try {
            PerDiemRate saved = perDiem.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_PER_DIEM", "Changed the " + saved.getCategory() + " per diem: " + saved.getCurrencyCode() + " meals "
                    + saved.getMeal().toPlainString() + ", accommodation " + saved.getAccommodation().toPlainString() + ", incidentals "
                    + saved.getIncidentals().toPlainString(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("perDiem.saved", messages.get("perDiem.category." + saved.getCategory())));
            return "redirect:/payroll/per-diem";
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_PER_DIEM", "Failed to change a per diem rate: " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
                model.addAttribute("formErrors", result.getFieldErrors());
            } else {
                model.addAttribute("flashError", error);
            }
            return form(model, dto, rate, 0);
        }
    }

    private String form(Model model, PerDiemRateDto dto, PerDiemRate rate, int page) {
        model.addAttribute("rateDto", dto);
        model.addAttribute("rate", rate);
        model.addAttribute("currencies", perDiem.currencies());
        model.addAttribute("history", dataChangeService.history("PerDiemRate", rate.getId().toString(), Paging.page(page), Paging.SIZE));
        return "per-diem/form";
    }
}
