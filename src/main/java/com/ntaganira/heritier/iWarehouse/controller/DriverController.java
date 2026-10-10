package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.DriverDto;
import com.ntaganira.heritier.iWarehouse.entity.Driver;
import com.ntaganira.heritier.iWarehouse.entity.Vehicle;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.AttachmentService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.DriverService;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : DriverController.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Drivers (FLT-03, FLT-04): list, register a user as a driver, edit their licence, deactivate; each page shows
 *               the licence expiry, the trip they are on, their trips and History. Their national ID, licence number and
 *               licence copies are personal data (NFR-12): in full only with PERM_VIEW_DRIVER_DATA, which the forms need
 *               too. PAGE_DRIVERS + PERM_VIEW_DRIVER; changes need PERM_MANAGE_DRIVER.
 * </pre>
 */
@Controller
@RequestMapping("/drivers")
public class DriverController {

    static final String MODULE = "Fleet";

    private final DriverService driverService;
    private final VehicleService vehicleService;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public DriverController(DriverService driverService, VehicleService vehicleService, AttachmentService attachmentService,
                            DataChangeService dataChangeService, ActivityLogService activityLogService, Messages messages) {
        this.driverService = driverService;
        this.vehicleService = vehicleService;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_DRIVERS') and hasAuthority('PERM_VIEW_DRIVER')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        Page<Driver> drivers = driverService.findPage(search, status, Paging.page(page), Paging.SIZE);
        Map<UUID, FleetPapers.Stage> licences = new HashMap<>();
        drivers.forEach(d -> licences.put(d.getId(), driverService.licence(d)));
        model.addAttribute("drivers", drivers);
        model.addAttribute("licences", licences);
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "drivers/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_DRIVERS') and hasAuthority('PERM_VIEW_DRIVER')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "details") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        boolean personal = AppUserPrincipal.currentHas("PERM_VIEW_DRIVER_DATA");
        List<String> tabs = personal ? List.of("details", "trips", "documents", "history") : List.of("details", "trips", "history");
        String open = tabs.contains(tab) ? tab : "details";
        Driver driver = driverService.findDetailed(id);
        model.addAttribute("driver", driver);
        model.addAttribute("personal", personal);
        // Personal data (NFR-12): in full only for those who may see it, the last four characters otherwise
        model.addAttribute("nationalIdShown", personal ? driver.getNationalId() : Driver.masked(driver.getNationalId()));
        model.addAttribute("licenceNumberShown", personal ? driver.getLicenceNumber() : Driver.masked(driver.getLicenceNumber()));
        model.addAttribute("licence", driverService.licence(driver));
        model.addAttribute("alertDays", vehicleService.alertDays());
        model.addAttribute("onTheRoad", driverService.onTheRoad(id).orElse(null));
        model.addAttribute("trips", driverService.trips(id, Paging.pageOf("trips", open, page), Paging.SIZE));
        if (personal) {
            model.addAttribute("documents", attachmentService.page(AttachmentOwner.DRIVER, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        }
        model.addAttribute("history", dataChangeService.history("Driver", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "drivers/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_DRIVERS') and hasAuthority('PERM_MANAGE_DRIVER') and hasAuthority('PERM_VIEW_DRIVER_DATA')")
    public String createForm(@RequestParam(required = false) Long user, Model model) {
        DriverDto dto = new DriverDto();
        dto.setUserId(user);
        return form(model, dto);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_DRIVERS') and hasAuthority('PERM_MANAGE_DRIVER') and hasAuthority('PERM_VIEW_DRIVER_DATA')")
    public String create(@Valid @ModelAttribute("driverDto") DriverDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Driver driver = driverService.create(dto);
            activityLogService.record(MODULE, "CREATE_DRIVER", "Registered " + driver.getUsername() + " as a driver (licence "
                    + driver.getLicenceCategory() + ", expires " + driver.getLicenceExpiry() + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("driver.created", driver.getUser().getFullName()));
            return "redirect:/drivers/" + driver.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_DRIVER", "Failed to register a driver: " + messages.get(e.getMessageKey(), e.getArgs()),
                    ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_DRIVERS') and hasAuthority('PERM_MANAGE_DRIVER') and hasAuthority('PERM_VIEW_DRIVER_DATA')")
    public String editForm(@PathVariable UUID id, Model model) {
        Driver d = driverService.findDetailed(id);
        DriverDto dto = new DriverDto();
        dto.setId(id);
        dto.setUserId(d.getUser().getId());
        dto.setNationalId(d.getNationalId());
        dto.setLicenceNumber(d.getLicenceNumber());
        dto.setLicenceCategory(d.getLicenceCategory());
        dto.setLicenceExpiry(d.getLicenceExpiry());
        dto.setDefaultVehicleId(d.getDefaultVehicle() == null ? null : d.getDefaultVehicle().getId());
        return form(model, dto);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_DRIVERS') and hasAuthority('PERM_MANAGE_DRIVER') and hasAuthority('PERM_VIEW_DRIVER_DATA')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("driverDto") DriverDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        dto.setId(id);
        dto.setUserId(driverService.findDetailed(id).getUser().getId());   // fixed after registration
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Driver driver = driverService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_DRIVER", "Updated driver " + driver.getUsername(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("driver.updated", driver.getUser().getFullName()));
            return "redirect:/drivers/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_DRIVER", "Failed to update driver " + usernameOf(id) + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_DRIVERS') and hasAuthority('PERM_MANAGE_DRIVER')")
    public String disable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, false, redirect);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_DRIVERS') and hasAuthority('PERM_MANAGE_DRIVER')")
    public String enable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, true, redirect);
    }

    private String setEnabled(UUID id, boolean enabled, RedirectAttributes redirect) {
        String action = enabled ? "ENABLE_DRIVER" : "DISABLE_DRIVER";
        try {
            Driver driver = driverService.setEnabled(id, enabled);
            activityLogService.record(MODULE, action, (enabled ? "Activated" : "Deactivated") + " driver " + driver.getUsername(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(enabled ? "driver.enabledMsg" : "driver.disabledMsg",
                    driver.getUser().getFullName()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to change status of driver " + usernameOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/drivers/" + id;
    }

    private String usernameOf(UUID id) {
        try {
            return driverService.findDetailed(id).getUsername();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }

    private String form(Model model, DriverDto dto) {
        Driver current = dto.getId() == null ? null : driverService.findDetailed(dto.getId());
        model.addAttribute("driverDto", dto);
        model.addAttribute("driver", current);
        model.addAttribute("candidates", current == null ? driverService.candidates() : List.of());
        // Active vehicles, and the driver's own even if it was deactivated since, so saving keeps it
        List<Vehicle> vehicles = new ArrayList<>(vehicleService.active());
        if (current != null && current.getDefaultVehicle() != null && !current.getDefaultVehicle().isEnabled()) {
            vehicles.add(current.getDefaultVehicle());
        }
        model.addAttribute("vehicles", vehicles);
        return "drivers/form";
    }

    private String invalid(Model model, DriverDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    private String rejected(Model model, DriverDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }
}
