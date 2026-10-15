package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.VehicleCostDto;
import com.ntaganira.heritier.iWarehouse.entity.VehicleCost;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.AttachmentService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.VehicleCostService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : VehicleCostController.java
 * - Date      : 2026. 10. 12.
 * - User      : Hntaganira
 * - Desc      : Vehicle costs: the list, recording one (from a vehicle's or a trip's page, or empty), a cost with its
 *               journal, receipt and History; approving or rejecting it (another person), withdrawing it (who recorded
 *               it), reversing it once posted. PAGE_VEHICLE_COSTS + PERM_VIEW_VEHICLE_COST; recording
 *               PERM_RECORD_VEHICLE_COST, deciding PERM_APPROVE_VEHICLE_COST, reversing PERM_REVERSE_VEHICLE_COST.
 * </pre>
 */
@Controller
@RequestMapping("/vehicle-costs")
public class VehicleCostController {

    static final String MODULE = "Vehicle Costs";
    private static final int REASON_MAX = 255;
    private static final String BASE = "/vehicle-costs";
    private static final String VIEW = "hasAuthority('PAGE_VEHICLE_COSTS') and hasAuthority('PERM_VIEW_VEHICLE_COST')";
    private static final String RECORD = "hasAuthority('PAGE_VEHICLE_COSTS') and hasAuthority('PERM_RECORD_VEHICLE_COST')";

    private final VehicleCostService costService;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public VehicleCostController(VehicleCostService costService, AttachmentService attachmentService, DataChangeService dataChangeService,
                                 ActivityLogService activityLogService, Messages messages, NumberFormats num) {
        this.costService = costService;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(required = false) UUID vehicle, @RequestParam(required = false) String category,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("costs", costService.findPage(search, status, vehicle, category, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", VehicleCostStatus.values());
        model.addAttribute("categories", VehicleCostCategory.values());
        model.addAttribute("vehicles", costService.vehicles());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("vehicle", vehicle == null ? null : vehicle.toString());
        model.addAttribute("category", category);
        model.addAttribute("pending", costService.pendingCount());
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status, "vehicle",
                vehicle == null ? null : vehicle.toString(), "category", category));
        return "vehicle-costs/list";
    }

    /** Records a cost: empty, or for a vehicle or a trip (from their pages). */
    @GetMapping("/new")
    @PreAuthorize(RECORD)
    public String form(@RequestParam(required = false) UUID vehicle, @RequestParam(required = false) UUID trip, Model model) {
        return form(model, costService.newForm(vehicle, trip));
    }

    @PostMapping("/new")
    @PreAuthorize(RECORD)
    public String create(@Valid @ModelAttribute("costDto") VehicleCostDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto);
        }
        try {
            VehicleCost cost = costService.record(dto);
            activityLogService.record(MODULE, "CREATE_VEHICLE_COST", "Recorded vehicle cost " + cost.getNumber() + " of " + cost.getVehicle().getPlate()
                    + " (" + cost.getCategory() + ", " + num.money(cost.getAmount()) + " RWF, " + cost.getPayment() + "): " + cost.getDescription()
                    + (cost.isPending() ? "; waits for approval" : ""), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(cost.isPending() ? "vehicleCost.waiting" : "vehicleCost.posted",
                    cost.getNumber(), num.money(cost.getAmount())));
            return "redirect:" + BASE + "/" + cost.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_VEHICLE_COST", "Failed to record a vehicle cost: " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
                model.addAttribute("formErrors", result.getFieldErrors());
            } else {
                model.addAttribute("flashError", error);
            }
            return form(model, dto);
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(required = false) String tab, @RequestParam(defaultValue = "0") int page, Model model) {
        VehicleCost cost = costService.findDetailed(id);
        String open = "documents".equals(tab) || "history".equals(tab) ? tab : "documents";
        model.addAttribute("cost", cost);
        model.addAttribute("journals", costService.journals(cost));
        model.addAttribute("mine", costService.isRequester(cost));
        model.addAttribute("authority", cost.getStatus() == VehicleCostStatus.PENDING_APPROVAL ? costService.authorityOf(cost) : null);
        model.addAttribute("limit", costService.approvalLimit());
        model.addAttribute("today", costService.today());
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.VEHICLE_COST, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("VehicleCost", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "vehicle-costs/view";
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PAGE_VEHICLE_COSTS') and hasAuthority('PERM_APPROVE_VEHICLE_COST')")
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        if (note != null && note.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:" + BASE + "/" + id;
        }
        try {
            VehicleCost cost = costService.approve(id, note);
            activityLogService.record(MODULE, "APPROVE_VEHICLE_COST", "Approved vehicle cost " + cost.getNumber() + " of " + cost.getRequestedBy()
                    + ", " + num.money(cost.getAmount()) + " RWF: " + cost.getDescription(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("vehicleCost.approved", cost.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "APPROVE_VEHICLE_COST", "Failed to approve vehicle cost " + numberOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:" + BASE + "/" + id;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAGE_VEHICLE_COSTS') and hasAuthority('PERM_APPROVE_VEHICLE_COST')")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return decideWithReason(id, reason, redirect, true);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(RECORD)
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return decideWithReason(id, reason, redirect, false);
    }

    /** Reverses a posted cost on a day (today by default), with a reason. */
    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasAuthority('PAGE_VEHICLE_COSTS') and hasAuthority('PERM_REVERSE_VEHICLE_COST')")
    public String reverse(@PathVariable UUID id, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reversalDate,
                          @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:" + BASE + "/" + id;
        }
        try {
            VehicleCost cost = AuditContext.withReason(reason.trim(), () -> costService.reverse(id, reversalDate, reason));
            activityLogService.record(MODULE, "REVERSE_VEHICLE_COST", "Reversed vehicle cost " + cost.getNumber() + " on " + cost.getReversalDate()
                    + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("vehicleCost.reversed", cost.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "REVERSE_VEHICLE_COST", "Failed to reverse vehicle cost " + numberOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:" + BASE + "/" + id;
    }

    // ---------------------------------------------------------------- helpers

    private String decideWithReason(UUID id, String reason, RedirectAttributes redirect, boolean reject) {
        String action = reject ? "REJECT_VEHICLE_COST" : "CANCEL_VEHICLE_COST";
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:" + BASE + "/" + id;
        }
        try {
            VehicleCost cost = AuditContext.withReason(reason.trim(), () -> reject ? costService.reject(id, reason) : costService.cancel(id, reason));
            activityLogService.record(MODULE, action, (reject ? "Rejected" : "Withdrew") + " vehicle cost " + cost.getNumber() + ": " + reason.trim(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(reject ? "vehicleCost.rejectedMsg" : "vehicleCost.cancelledMsg", cost.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to " + (reject ? "reject" : "withdraw") + " vehicle cost " + numberOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:" + BASE + "/" + id;
    }

    private String form(Model model, VehicleCostDto dto) {
        model.addAttribute("costDto", dto);
        model.addAttribute("vehicles", costService.vehicles());
        model.addAttribute("trips", costService.trips(dto.getTripId()));
        model.addAttribute("garages", costService.garages());
        model.addAttribute("plans", costService.plans());
        model.addAttribute("categories", VehicleCostCategory.values());
        model.addAttribute("payments", VehicleCostPayment.values());
        model.addAttribute("limit", costService.approvalLimit());
        model.addAttribute("today", costService.today());
        return "vehicle-costs/form";
    }

    private String numberOf(UUID id) {
        try {
            return costService.findDetailed(id).getNumber();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }
}
