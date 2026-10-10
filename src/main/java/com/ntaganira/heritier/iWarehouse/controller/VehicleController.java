package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.VehicleDto;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.entity.Vehicle;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.AttachmentService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.FleetPapers;
import com.ntaganira.heritier.iWarehouse.service.VehicleService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : VehicleController.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Vehicles (FLT-01, FLT-02, FLT-04): list, add, edit, deactivate; each page shows its papers, the trip it is
 *               on, its trips, the stock on board, its documents and History. PAGE_VEHICLES + PERM_VIEW_VEHICLE; changes need
 *               PERM_MANAGE_VEHICLE.
 * </pre>
 */
@Controller
@RequestMapping("/vehicles")
public class VehicleController {

    static final String MODULE = "Fleet";

    private final VehicleService vehicleService;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public VehicleController(VehicleService vehicleService, AttachmentService attachmentService, DataChangeService dataChangeService,
                             ActivityLogService activityLogService, Messages messages) {
        this.vehicleService = vehicleService;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_VEHICLES') and hasAuthority('PERM_VIEW_VEHICLE')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        Page<Vehicle> vehicles = vehicleService.findPage(search, status, Paging.page(page), Paging.SIZE);
        // Insurance and inspection state per vehicle, keyed by paper name for the template
        Map<UUID, Map<String, FleetPapers.Stage>> papers = new HashMap<>();
        vehicles.forEach(v -> papers.put(v.getId(), named(vehicleService.papers(v))));
        model.addAttribute("vehicles", vehicles);
        model.addAttribute("papers", papers);
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "vehicles/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_VEHICLES') and hasAuthority('PERM_VIEW_VEHICLE')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "details") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        String open = List.of("details", "trips", "stock", "documents", "history").contains(tab) ? tab : "details";
        Vehicle vehicle = vehicleService.findDetailed(id);
        List<StockUnit> onBoard = vehicleService.unitsOnBoard(vehicle);
        model.addAttribute("vehicle", vehicle);
        model.addAttribute("papers", named(vehicleService.papers(vehicle)));
        model.addAttribute("alertDays", vehicleService.alertDays());
        model.addAttribute("onTheRoad", vehicleService.onTheRoad(id).orElse(null));
        model.addAttribute("load", vehicleService.onBoard(onBoard));
        model.addAttribute("units", Paging.of(onBoard, Paging.pageOf("stock", open, page)));
        model.addAttribute("trips", vehicleService.trips(id, Paging.pageOf("trips", open, page), Paging.SIZE));
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.VEHICLE, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("Vehicle", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "vehicles/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_VEHICLES') and hasAuthority('PERM_MANAGE_VEHICLE')")
    public String createForm(Model model) {
        return form(model, new VehicleDto());
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_VEHICLES') and hasAuthority('PERM_MANAGE_VEHICLE')")
    public String create(@Valid @ModelAttribute("vehicleDto") VehicleDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Vehicle vehicle = vehicleService.create(dto);
            activityLogService.record(MODULE, "CREATE_VEHICLE", "Added vehicle " + vehicle.getPlate() + " (" + vehicle.getModel()
                    + ", " + vehicle.getMaxLoadKg() + " kg, " + vehicle.getMaxPieces() + " pieces) with its location "
                    + vehicle.getLocation().getCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("vehicle.created", vehicle.getPlate(), vehicle.getLocation().getCode()));
            return "redirect:/vehicles/" + vehicle.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_VEHICLE", "Failed to add vehicle " + dto.getPlate() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_VEHICLES') and hasAuthority('PERM_MANAGE_VEHICLE')")
    public String editForm(@PathVariable UUID id, Model model) {
        Vehicle v = vehicleService.findDetailed(id);
        VehicleDto dto = new VehicleDto();
        dto.setId(id);
        dto.setPlate(v.getPlate());
        dto.setModel(v.getModel());
        dto.setRackConfiguration(v.getRackConfiguration());
        dto.setMaxLoadKg(v.getMaxLoadKg());
        dto.setMaxPieces(v.getMaxPieces());
        dto.setInsuranceExpiry(v.getInsuranceExpiry());
        dto.setInspectionExpiry(v.getInspectionExpiry());
        dto.setNotes(v.getNotes());
        return form(model, dto);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_VEHICLES') and hasAuthority('PERM_MANAGE_VEHICLE')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("vehicleDto") VehicleDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        dto.setId(id);
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Vehicle vehicle = vehicleService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_VEHICLE", "Updated vehicle " + vehicle.getPlate(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("vehicle.updated", vehicle.getPlate()));
            return "redirect:/vehicles/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_VEHICLE", "Failed to update vehicle " + plateOf(id) + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_VEHICLES') and hasAuthority('PERM_MANAGE_VEHICLE')")
    public String disable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, false, redirect);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_VEHICLES') and hasAuthority('PERM_MANAGE_VEHICLE')")
    public String enable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, true, redirect);
    }

    private String setEnabled(UUID id, boolean enabled, RedirectAttributes redirect) {
        String action = enabled ? "ENABLE_VEHICLE" : "DISABLE_VEHICLE";
        try {
            Vehicle vehicle = vehicleService.setEnabled(id, enabled);
            activityLogService.record(MODULE, action, (enabled ? "Activated" : "Deactivated") + " vehicle " + vehicle.getPlate()
                    + " and its location " + vehicle.getLocation().getCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(enabled ? "vehicle.enabledMsg" : "vehicle.disabledMsg", vehicle.getPlate()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to change status of vehicle " + plateOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/vehicles/" + id;
    }

    private static Map<String, FleetPapers.Stage> named(Map<FleetPapers.Paper, FleetPapers.Stage> papers) {
        Map<String, FleetPapers.Stage> named = new HashMap<>();
        papers.forEach((paper, stage) -> named.put(paper.name(), stage));
        return named;
    }

    private String plateOf(UUID id) {
        try {
            return vehicleService.findDetailed(id).getPlate();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }

    private String form(Model model, VehicleDto dto) {
        model.addAttribute("vehicleDto", dto);
        model.addAttribute("vehicle", dto.getId() == null ? null : vehicleService.findDetailed(dto.getId()));
        return "vehicles/form";
    }

    private String invalid(Model model, VehicleDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    private String rejected(Model model, VehicleDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }
}
