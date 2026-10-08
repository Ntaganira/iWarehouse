package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.dto.PermissionDto;
import com.ntaganira.heritier.iWarehouse.entity.Permission;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.PermissionService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : PermissionController.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Permission catalogue (ADM-01): list, relabel, activate/deactivate. Permissions are
 *               created by module migrations, never from the UI, because code checks them by code.
 * </pre>
 */
@Controller
@RequestMapping("/permissions")
public class PermissionController {

    private static final int PAGE_SIZE = 15;
    private static final String MODULE = "Permission Management";

    private final PermissionService permissionService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public PermissionController(PermissionService permissionService, DataChangeService dataChangeService,
                                ActivityLogService activityLogService, Messages messages) {
        this.permissionService = permissionService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_PERMISSIONS') and hasAuthority('PERM_VIEW_PERMISSION')")
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String search,
                       @RequestParam(required = false) String module,
                       Model model) {
        model.addAttribute("permissions", permissionService.findPage(search, module, Math.max(page, 0), PAGE_SIZE));
        model.addAttribute("roleCounts", permissionService.roleCounts());
        model.addAttribute("protectedModules", PermissionService.PROTECTED_MODULES);
        model.addAttribute("modules", permissionService.findModules());
        model.addAttribute("search", search);
        model.addAttribute("module", module);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "module", module));
        return "permissions/list";
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PERMISSIONS') and hasAuthority('PERM_EDIT_PERMISSION')")
    public String editForm(@PathVariable Long id, Model model) {
        Permission permission = permissionService.findById(id);
        PermissionDto dto = new PermissionDto();
        dto.setId(id);
        dto.setName(permission.getName());
        dto.setDescription(permission.getDescription());
        return form(model, dto, permission);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PERMISSIONS') and hasAuthority('PERM_EDIT_PERMISSION')")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("permissionDto") PermissionDto dto,
                         BindingResult result, Model model, RedirectAttributes redirect) {
        dto.setId(id);
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto, permissionService.findById(id));
        }
        Permission permission = permissionService.update(id, dto);
        activityLogService.record(MODULE, "UPDATE_PERMISSION", "Updated permission " + permission.getCode(),
                ActivityStatus.SUCCESS);
        redirect.addFlashAttribute("flashSuccess", messages.get("perm.updated", permission.getCode()));
        return "redirect:/permissions";
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_PERMISSIONS') and hasAuthority('PERM_EDIT_PERMISSION')")
    public String disable(@PathVariable Long id, @RequestHeader(value = "Referer", required = false) String referer,
                          RedirectAttributes redirect) {
        return setEnabled(id, false, referer, redirect);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_PERMISSIONS') and hasAuthority('PERM_EDIT_PERMISSION')")
    public String enable(@PathVariable Long id, @RequestHeader(value = "Referer", required = false) String referer,
                         RedirectAttributes redirect) {
        return setEnabled(id, true, referer, redirect);
    }

    private String setEnabled(Long id, boolean enabled, String referer, RedirectAttributes redirect) {
        String action = enabled ? "ENABLE_PERMISSION" : "DISABLE_PERMISSION";
        try {
            Permission permission = permissionService.setEnabled(id, enabled);
            activityLogService.record(MODULE, action,
                    (enabled ? "Activated" : "Deactivated") + " permission " + permission.getCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess",
                    messages.get(enabled ? "perm.enabledMsg" : "perm.disabledMsg", permission.getCode()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to change status of permission #" + id + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/permissions" + (referer != null && referer.contains("/permissions?")
                ? referer.replaceFirst("^[^?]*", "") : "");
    }

    private String form(Model model, PermissionDto dto, Permission permission) {
        model.addAttribute("permissionDto", dto);
        model.addAttribute("permission", permission);
        model.addAttribute("history", dataChangeService.history("Permission", permission.getId().toString(), 0, 10));
        return "permissions/form";
    }
}
