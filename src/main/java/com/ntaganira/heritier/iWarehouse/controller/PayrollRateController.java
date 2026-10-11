package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.PayrollRateDto;
import com.ntaganira.heritier.iWarehouse.entity.PayrollRateSet;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.PayrollRateService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : PayrollRateController.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : Payroll rates (/payroll/rates): the PAYE bands and rates and the RSSB and CBHI rates in force from each date.
 *               Anyone with the page reads them; PERM_MANAGE_PAYROLL_RATES records a new set or corrects one no submitted,
 *               posted or paid run uses.
 * </pre>
 */
@Controller
@RequestMapping("/payroll/rates")
public class PayrollRateController {

    static final String MODULE = "Payroll";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PayrollRateService rateService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public PayrollRateController(PayrollRateService rateService, DataChangeService dataChangeService, ActivityLogService activityLogService,
                                 Messages messages) {
        this.rateService = rateService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_PAYROLL_RATES')")
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        List<PayrollRateSet> all = rateService.all();
        Map<UUID, Boolean> used = new HashMap<>();
        all.forEach(s -> used.put(s.getId(), rateService.isUsed(s.getId())));
        model.addAttribute("sets", Paging.of(all, Paging.page(page)));
        model.addAttribute("used", used);
        model.addAttribute("inForce", rateService.inForceToday().map(PayrollRateSet::getId).orElse(null));
        model.addAttribute("today", rateService.today());
        return "payroll/rates/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_PAYROLL_RATES')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("set", rateService.findById(id));
        model.addAttribute("used", rateService.isUsed(id));
        model.addAttribute("history", dataChangeService.history("PayrollRateSet", id.toString(), Paging.page(page), Paging.SIZE));
        return "payroll/rates/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_PAYROLL_RATES') and hasAuthority('PERM_MANAGE_PAYROLL_RATES')")
    public String createForm(Model model) {
        return form(model, rateService.newForm());
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_PAYROLL_RATES') and hasAuthority('PERM_MANAGE_PAYROLL_RATES')")
    public String create(@Valid @ModelAttribute("rateDto") PayrollRateDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            PayrollRateSet set = rateService.create(dto);
            activityLogService.record(MODULE, "CREATE_PAYROLL_RATES", "Recorded the payroll rates in force from " + set.getEffectiveFrom().format(DAY),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("payrollRate.created", set.getEffectiveFrom().format(DAY)));
            return "redirect:/payroll/rates/" + set.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_PAYROLL_RATES", "Failed to record payroll rates: " + messages.get(e.getMessageKey(), e.getArgs()),
                    ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PAYROLL_RATES') and hasAuthority('PERM_MANAGE_PAYROLL_RATES')")
    public String editForm(@PathVariable UUID id, Model model) {
        return form(model, PayrollRateDto.of(rateService.findById(id)));
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PAYROLL_RATES') and hasAuthority('PERM_MANAGE_PAYROLL_RATES')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("rateDto") PayrollRateDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        dto.setId(id);
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            PayrollRateSet set = rateService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_PAYROLL_RATES", "Corrected the payroll rates in force from " + set.getEffectiveFrom().format(DAY),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("payrollRate.updated", set.getEffectiveFrom().format(DAY)));
            return "redirect:/payroll/rates/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_PAYROLL_RATES", "Failed to correct payroll rates: " + messages.get(e.getMessageKey(), e.getArgs()),
                    ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    private String form(Model model, PayrollRateDto dto) {
        model.addAttribute("rateDto", dto);
        return "payroll/rates/form";
    }

    private String invalid(Model model, PayrollRateDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    private String rejected(Model model, PayrollRateDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }
}
