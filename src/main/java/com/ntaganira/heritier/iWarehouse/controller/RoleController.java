package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.RoleDto;
import com.ntaganira.heritier.iWarehouse.entity.AppPage;
import com.ntaganira.heritier.iWarehouse.entity.Role;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : RoleController.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Roles screens (ADM-01), ported from iVura: list, detail with History, add/edit with
 *               page and permission grants, activate/deactivate (no delete). PAGE_ROLES + PERM_x_ROLE.
 * </pre>
 */
@Controller
@RequestMapping("/roles")
public class RoleController {
    private static final String MODULE = "Role Management";

    private final RoleService roleService;
    private final PermissionService permissionService;
    private final UserService userService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public RoleController(RoleService roleService, PermissionService permissionService, UserService userService,
                          DataChangeService dataChangeService, ActivityLogService activityLogService,
                          Messages messages) {
        this.roleService = roleService;
        this.permissionService = permissionService;
        this.userService = userService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_ROLES') and hasAuthority('PERM_VIEW_ROLE')")
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String search,
                       @RequestParam(required = false) String status,
                       Model model) {
        model.addAttribute("roles", roleService.findPage(search, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("userCounts", roleService.userCounts());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "roles/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_ROLES') and hasAuthority('PERM_VIEW_ROLE')")
    public String view(@PathVariable Long id, @RequestParam(defaultValue = "access") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        String open = List.of("access", "members", "history").contains(tab) ? tab : "access";
        Role role = roleService.findById(id);
        model.addAttribute("role", role);
        model.addAttribute("isAdminRole", RoleService.isAdmin(role));
        model.addAttribute("grantedPermissions", RoleService.permissionsByModule(role));
        model.addAttribute("grantedPages", role.getPages().stream()
                .sorted(Comparator.comparingInt(AppPage::getSortOrder))
                .toList());
        model.addAttribute("members", Paging.of(userService.findByRole(id), Paging.pageOf("members", open, page)));
        model.addAttribute("history", dataChangeService.history("Role", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "roles/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_ROLES') and hasAuthority('PERM_CREATE_ROLE')")
    public String createForm(Model model) {
        return form(model, new RoleDto(), false);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_ROLES') and hasAuthority('PERM_CREATE_ROLE')")
    public String create(@Valid @ModelAttribute("roleDto") RoleDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result, false);
        }
        try {
            Role role = roleService.create(dto);
            activityLogService.record(MODULE, "CREATE_ROLE", "Created role " + role.getCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("role.created", role.getCode()));
            return "redirect:/roles/" + role.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_ROLE", "Failed to create role " + dto.getCode() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e, false);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_ROLES') and hasAuthority('PERM_EDIT_ROLE')")
    public String editForm(@PathVariable Long id, Model model) {
        Role role = roleService.findById(id);
        RoleDto dto = new RoleDto();
        dto.setId(role.getId());
        dto.setCode(role.getCode());
        dto.setDescription(role.getDescription());
        role.getPermissions().forEach(p -> dto.getPermissionIds().add(p.getId()));
        role.getPages().forEach(p -> dto.getPageIds().add(p.getId()));
        return form(model, dto, RoleService.isAdmin(role));
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_ROLES') and hasAuthority('PERM_EDIT_ROLE')")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("roleDto") RoleDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        Role existing = roleService.findById(id);
        boolean adminRole = RoleService.isAdmin(existing);
        dto.setId(id);
        dto.setCode(existing.getCode()); // fixed after creation
        if (result.hasErrors()) {
            return invalid(model, dto, result, adminRole);
        }
        try {
            Role role = roleService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_ROLE", "Updated role " + role.getCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("role.updated", role.getCode()));
            return "redirect:/roles/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_ROLE", "Failed to update role " + dto.getCode() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e, adminRole);
        }
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_ROLES') and hasAuthority('PERM_EDIT_ROLE')")
    public String disable(@PathVariable Long id, @RequestHeader(value = "Referer", required = false) String referer,
                          RedirectAttributes redirect) {
        return setEnabled(id, false, referer, redirect);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_ROLES') and hasAuthority('PERM_EDIT_ROLE')")
    public String enable(@PathVariable Long id, @RequestHeader(value = "Referer", required = false) String referer,
                         RedirectAttributes redirect) {
        return setEnabled(id, true, referer, redirect);
    }

    private String setEnabled(Long id, boolean enabled, String referer, RedirectAttributes redirect) {
        String action = enabled ? "ENABLE_ROLE" : "DISABLE_ROLE";
        try {
            Role role = roleService.setEnabled(id, enabled);
            activityLogService.record(MODULE, action, (enabled ? "Activated" : "Deactivated") + " role " + role.getCode(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess",
                    messages.get(enabled ? "role.enabledMsg" : "role.disabledMsg", role.getCode()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to change status of role #" + id + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return referer != null && referer.matches(".*/roles/?(\\?.*)?$")
                ? "redirect:/roles" + referer.replaceFirst("^[^?]*", "")
                : "redirect:/roles/" + id;
    }

    private String form(Model model, RoleDto dto, boolean adminRole) {
        model.addAttribute("roleDto", dto);
        model.addAttribute("isAdminRole", adminRole);
        model.addAttribute("permissionsByModule", permissionService.byModule());
        model.addAttribute("pagesByModule", roleService.pagesByModule());
        return "roles/form";
    }

    private String invalid(Model model, RoleDto dto, BindingResult result, boolean adminRole) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto, adminRole);
    }

    private String rejected(Model model, RoleDto dto, BindingResult result, BusinessException e, boolean adminRole) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            // Pass the args too: th:errors resolves the message again from its code.
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result, adminRole);
    }
}
