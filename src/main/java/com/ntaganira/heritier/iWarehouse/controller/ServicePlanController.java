package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.dto.ServicePlanDto;
import com.ntaganira.heritier.iWarehouse.entity.Vehicle;
import com.ntaganira.heritier.iWarehouse.entity.VehicleServicePlan;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.ServicePlanService;
import com.ntaganira.heritier.iWarehouse.service.VehicleService;
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
 * - File      : ServicePlanController.java
 * - Date      : 2026. 10. 12.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A vehicle's service plans: adding one, changing it, deactivating or activating it; back to the vehicle's
 *               Service tab. PAGE_VEHICLES + PERM_MANAGE_SERVICE_PLANS.
 * </pre>
 */
@Controller
@RequestMapping("/vehicles/{vehicleId}/service-plans")
public class ServicePlanController {

    private static final String MANAGE = "hasAuthority('PAGE_VEHICLES') and hasAuthority('PERM_MANAGE_SERVICE_PLANS')";

    private final ServicePlanService planService;
    private final VehicleService vehicleService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public ServicePlanController(ServicePlanService planService, VehicleService vehicleService, ActivityLogService activityLogService,
                                 Messages messages) {
        this.planService = planService;
        this.vehicleService = vehicleService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping("/new")
    @PreAuthorize(MANAGE)
    public String createForm(@PathVariable UUID vehicleId, Model model) {
        Vehicle vehicle = vehicleService.findDetailed(vehicleId);
        return form(model, vehicle, null, planService.newForm(vehicle));
    }

    @PostMapping("/new")
    @PreAuthorize(MANAGE)
    public String create(@PathVariable UUID vehicleId, @Valid @ModelAttribute("planDto") ServicePlanDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        Vehicle vehicle = vehicleService.findDetailed(vehicleId);
        if (result.hasErrors()) {
            return form(model, vehicle, null, dto);
        }
        try {
            VehicleServicePlan plan = planService.create(vehicleId, dto);
            activityLogService.record(VehicleController.MODULE, "CREATE_SERVICE_PLAN", "Added the service plan \"" + plan.getName() + "\" of "
                    + vehicle.getPlate() + " (" + every(plan) + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("servicePlan.created", plan.getName(), vehicle.getPlate()));
            return back(vehicleId);
        } catch (BusinessException e) {
            return failed(e, "CREATE_SERVICE_PLAN", "Failed to add a service plan to " + vehicle.getPlate(), result, model, vehicle, null, dto);
        }
    }

    @GetMapping("/{planId}/edit")
    @PreAuthorize(MANAGE)
    public String editForm(@PathVariable UUID vehicleId, @PathVariable UUID planId, Model model) {
        Vehicle vehicle = vehicleService.findDetailed(vehicleId);
        VehicleServicePlan plan = plan(vehicleId, planId);
        return form(model, vehicle, plan, planService.form(plan));
    }

    @PostMapping("/{planId}/edit")
    @PreAuthorize(MANAGE)
    public String update(@PathVariable UUID vehicleId, @PathVariable UUID planId, @Valid @ModelAttribute("planDto") ServicePlanDto dto,
                         BindingResult result, Model model, RedirectAttributes redirect) {
        Vehicle vehicle = vehicleService.findDetailed(vehicleId);
        VehicleServicePlan plan = plan(vehicleId, planId);
        if (result.hasErrors()) {
            return form(model, vehicle, plan, dto);
        }
        try {
            VehicleServicePlan saved = planService.update(planId, dto);
            activityLogService.record(VehicleController.MODULE, "UPDATE_SERVICE_PLAN", "Changed the service plan \"" + saved.getName() + "\" of "
                    + vehicle.getPlate() + " (" + every(saved) + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("servicePlan.saved", saved.getName()));
            return back(vehicleId);
        } catch (BusinessException e) {
            return failed(e, "UPDATE_SERVICE_PLAN", "Failed to change the service plan \"" + plan.getName() + "\" of " + vehicle.getPlate(),
                    result, model, vehicle, plan, dto);
        }
    }

    @PostMapping("/{planId}/deactivate")
    @PreAuthorize(MANAGE)
    public String deactivate(@PathVariable UUID vehicleId, @PathVariable UUID planId, RedirectAttributes redirect) {
        return setEnabled(vehicleId, planId, false, redirect);
    }

    @PostMapping("/{planId}/activate")
    @PreAuthorize(MANAGE)
    public String activate(@PathVariable UUID vehicleId, @PathVariable UUID planId, RedirectAttributes redirect) {
        return setEnabled(vehicleId, planId, true, redirect);
    }

    // ---------------------------------------------------------------- helpers

    private String setEnabled(UUID vehicleId, UUID planId, boolean enabled, RedirectAttributes redirect) {
        Vehicle vehicle = vehicleService.findDetailed(vehicleId);
        VehicleServicePlan plan = planService.setEnabled(plan(vehicleId, planId).getId(), enabled);
        activityLogService.record(VehicleController.MODULE, "UPDATE_SERVICE_PLAN", (enabled ? "Activated" : "Deactivated") + " the service plan \""
                + plan.getName() + "\" of " + vehicle.getPlate(), ActivityStatus.SUCCESS);
        redirect.addFlashAttribute("flashSuccess", messages.get(enabled ? "servicePlan.activated" : "servicePlan.deactivated", plan.getName()));
        return back(vehicleId);
    }

    /** The plan, if it is this vehicle's; otherwise not found. */
    private VehicleServicePlan plan(UUID vehicleId, UUID planId) {
        VehicleServicePlan plan = planService.findById(planId);
        if (!plan.getVehicleId().equals(vehicleId)) {
            throw new NotFoundException("VehicleServicePlan", planId);
        }
        return plan;
    }

    private String failed(BusinessException e, String action, String what, BindingResult result, Model model, Vehicle vehicle,
                          VehicleServicePlan plan, ServicePlanDto dto) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        activityLogService.record(VehicleController.MODULE, action, what + ": " + error, ActivityStatus.FAILED);
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return form(model, vehicle, plan, dto);
    }

    private String form(Model model, Vehicle vehicle, VehicleServicePlan plan, ServicePlanDto dto) {
        model.addAttribute("vehicle", vehicle);
        model.addAttribute("plan", plan);
        model.addAttribute("planDto", dto);
        model.addAttribute("today", planService.today());
        return "vehicles/service-plan-form";
    }

    private static String back(UUID vehicleId) {
        return "redirect:/vehicles/" + vehicleId + "?tab=service";
    }

    private static String every(VehicleServicePlan plan) {
        return (plan.getEveryKm() == null ? "" : "every " + plan.getEveryKm() + " km")
                + (plan.getEveryKm() != null && plan.getEveryMonths() != null ? " or " : "")
                + (plan.getEveryMonths() == null ? "" : "every " + plan.getEveryMonths() + " months");
    }
}
