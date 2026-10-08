package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.LocationDto;
import com.ntaganira.heritier.iWarehouse.entity.Location;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import com.ntaganira.heritier.iWarehouse.enums.RackOrientation;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.LocationService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : LocationController.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Locations screens (MD-02, MD-03): the Site &gt; Zone &gt; Rack &gt; Slot tree, a detail page
 *               with sub-locations and History, add (under a parent), edit, activate and deactivate.
 *               PAGE_LOCATIONS + PERM_VIEW_LOCATION; changes PERM_MANAGE_LOCATION.
 * </pre>
 */
@Controller
@RequestMapping("/locations")
public class LocationController {

    static final String MODULE = "Locations";

    private final LocationService locationService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public LocationController(LocationService locationService, DataChangeService dataChangeService,
                              ActivityLogService activityLogService, Messages messages) {
        this.locationService = locationService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_LOCATIONS') and hasAuthority('PERM_VIEW_LOCATION')")
    public String list(@RequestParam(defaultValue = "false") boolean inactive, Model model) {
        model.addAttribute("rows", locationService.tree(inactive));
        model.addAttribute("inactive", inactive);
        return "locations/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_LOCATIONS') and hasAuthority('PERM_VIEW_LOCATION')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "details") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        String open = List.of("details", "children", "history").contains(tab) ? tab : "details";
        Location location = locationService.findById(id);
        model.addAttribute("location", location);
        model.addAttribute("ancestors", locationService.ancestors(location));
        model.addAttribute("children", Paging.of(locationService.children(id), Paging.pageOf("children", open, page)));
        model.addAttribute("history", dataChangeService.history("Location", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "locations/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_LOCATIONS') and hasAuthority('PERM_MANAGE_LOCATION')")
    public String createForm(@RequestParam(required = false) UUID parent, Model model, RedirectAttributes redirect) {
        LocationDto dto = new LocationDto();
        dto.setParentId(parent);
        if (parent != null) {
            Location parentLocation = locationService.findById(parent);
            LocationType childType = parentLocation.getType().getChildType();
            if (childType == null || !childType.isManual() || !parentLocation.isEnabled()) {
                redirect.addFlashAttribute("flashError", messages.get(childType == null ? "location.parent.noChildren"
                        : "location.parent.inactive", parentLocation.getCode()));
                return "redirect:/locations/" + parent;
            }
            dto.setCode(locationService.suggestCode(parentLocation));
        }
        dto.setOrientation(RackOrientation.VERTICAL);
        return form(model, dto);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_LOCATIONS') and hasAuthority('PERM_MANAGE_LOCATION')")
    public String create(@Valid @ModelAttribute("locationDto") LocationDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Location location = locationService.create(dto);
            activityLogService.record(MODULE, "CREATE_LOCATION", "Added " + describe(location), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("location.created", location.getCode()));
            return "redirect:/locations/" + location.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_LOCATION", "Failed to add location " + dto.getCode() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_LOCATIONS') and hasAuthority('PERM_MANAGE_LOCATION')")
    public String editForm(@PathVariable UUID id, Model model) {
        Location location = locationService.findById(id);
        LocationDto dto = new LocationDto();
        dto.setId(id);
        dto.setParentId(location.getParentId());
        dto.setCode(location.getCode());
        dto.setName(location.getName());
        dto.setOffcut(location.isOffcut());
        dto.setMaxWeightKg(location.getMaxWeightKg());
        dto.setMaxPieces(location.getMaxPieces());
        dto.setOrientation(location.getOrientation());
        return form(model, dto);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_LOCATIONS') and hasAuthority('PERM_MANAGE_LOCATION')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("locationDto") LocationDto dto,
                         BindingResult result, Model model, RedirectAttributes redirect) {
        dto.setId(id);
        dto.setParentId(locationService.findById(id).getParentId()); // fixed after creation
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Location location = locationService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_LOCATION", "Updated " + describe(location), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("location.updated", location.getCode()));
            return "redirect:/locations/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_LOCATION", "Failed to update location " + codeOf(id) + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_LOCATIONS') and hasAuthority('PERM_MANAGE_LOCATION')")
    public String disable(@PathVariable UUID id, @RequestParam(required = false) String from, RedirectAttributes redirect) {
        return setEnabled(id, false, from, redirect);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_LOCATIONS') and hasAuthority('PERM_MANAGE_LOCATION')")
    public String enable(@PathVariable UUID id, @RequestParam(required = false) String from, RedirectAttributes redirect) {
        return setEnabled(id, true, from, redirect);
    }

    /** {@code from=list} sends the user back to the tree (showing inactive ones), otherwise to the location. */
    private String setEnabled(UUID id, boolean enabled, String from, RedirectAttributes redirect) {
        String action = enabled ? "ENABLE_LOCATION" : "DISABLE_LOCATION";
        try {
            Location location = locationService.setEnabled(id, enabled);
            activityLogService.record(MODULE, action, (enabled ? "Activated " : "Deactivated ") + describe(location),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess",
                    messages.get(enabled ? "location.enabledMsg" : "location.disabledMsg", location.getCode()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to change status of location " + codeOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "list".equals(from) ? "redirect:/locations?inactive=true" : "redirect:/locations/" + id;
    }

    /** The location code for the activity log, or the id if the location does not exist. */
    private String codeOf(UUID id) {
        try {
            return locationService.findById(id).getCode();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }

    /** "rack WH-A-R01 (3000 kg, 30 pieces, VERTICAL)" for the activity log. */
    private static String describe(Location location) {
        StringBuilder s = new StringBuilder(location.getType().name().toLowerCase()).append(' ').append(location.getCode());
        if (location.isRack()) {
            List<String> limits = new ArrayList<>();
            if (location.getMaxWeightKg() != null) {
                limits.add(location.getMaxWeightKg() + " kg");
            }
            if (location.getMaxPieces() != null) {
                limits.add(location.getMaxPieces() + " pieces");
            }
            limits.add(location.getOrientation().name());
            if (location.isOffcut()) {
                limits.add("off-cuts");
            }
            s.append(" (").append(String.join(", ", limits)).append(')');
        }
        return s.toString();
    }

    private String form(Model model, LocationDto dto) {
        Location parent = dto.getParentId() == null ? null : locationService.findById(dto.getParentId());
        LocationType type = dto.getId() != null ? locationService.findById(dto.getId()).getType()
                : parent == null ? LocationType.SITE : parent.getType().getChildType();
        dto.setType(type);
        if (parent != null) {
            List<Location> path = new ArrayList<>(locationService.ancestors(parent));
            path.add(parent);
            model.addAttribute("parentPath", path);
        }
        model.addAttribute("locationDto", dto);
        model.addAttribute("parent", parent);
        model.addAttribute("orientations", RackOrientation.values());
        return "locations/form";
    }

    private String invalid(Model model, LocationDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    /** A business rule refused the form: show it next to its field, or as a toast. */
    private String rejected(Model model, LocationDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }
}
