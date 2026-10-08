package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.dto.UserDto;
import com.ntaganira.heritier.iWarehouse.entity.User;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.RoleService;
import com.ntaganira.heritier.iWarehouse.service.UserService;
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
 * - File      : UserController.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Users screens (ADM-01), ported from iVura. PAGE_USERS opens them; each action needs
 *               its PERM_ (create, edit, assign roles, disable, reset password). State changes are POST.
 * </pre>
 */
@Controller
@RequestMapping("/users")
public class UserController {

    private static final int PAGE_SIZE = 10;
    private static final String MODULE = "User Management";

    private final UserService userService;
    private final RoleService roleService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public UserController(UserService userService, RoleService roleService, DataChangeService dataChangeService,
                          ActivityLogService activityLogService, Messages messages) {
        this.userService = userService;
        this.roleService = roleService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_USERS') and hasAuthority('PERM_VIEW_USER')")
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String search,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) Long roleId,
                       Model model) {
        model.addAttribute("users", userService.findPage(search, status, roleId, Math.max(page, 0), PAGE_SIZE));
        model.addAttribute("roles", roleService.findAll());
        model.addAttribute("currentUserId", currentUserId());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("roleId", roleId);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status, "roleId", roleId));
        return "users/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_USERS') and hasAuthority('PERM_VIEW_USER')")
    public String view(@PathVariable Long id, Model model) {
        User user = userService.findById(id);
        model.addAttribute("user", user);
        model.addAttribute("isSelf", id.equals(currentUserId()));
        model.addAttribute("history", dataChangeService.history("User", id.toString(), 0, 20));
        return "users/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_USERS') and hasAuthority('PERM_CREATE_USER')")
    public String createForm(Model model) {
        return form(model, new UserDto());
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_USERS') and hasAuthority('PERM_CREATE_USER')")
    public String create(@Valid @ModelAttribute("userDto") UserDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            User user = userService.create(dto, canAssignRoles());
            activityLogService.record(MODULE, "CREATE_USER", "Created user " + user.getUsername(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("user.created", user.getUsername()));
            return "redirect:/users/" + user.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_USER", "Failed to create user " + dto.getUsername() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_USERS') and hasAuthority('PERM_EDIT_USER')")
    public String editForm(@PathVariable Long id, Model model) {
        User user = userService.findById(id);
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setFullName(user.getFullName());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        user.getRoles().forEach(r -> dto.getRoleIds().add(r.getId()));
        return form(model, dto);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_USERS') and hasAuthority('PERM_EDIT_USER')")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("userDto") UserDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        dto.setId(id);
        dto.setUsername(userService.findById(id).getUsername()); // fixed after creation
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            // Own roles are shown read-only (the service refuses self-changes too), so nothing to apply.
            boolean assignRoles = canAssignRoles() && !id.equals(currentUserId());
            User user = userService.update(id, dto, assignRoles, currentUserId());
            activityLogService.record(MODULE, "UPDATE_USER", "Updated user " + user.getUsername(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("user.updated", user.getUsername()));
            return "redirect:/users/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_USER", "Failed to update user " + dto.getUsername() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_USERS') and hasAuthority('PERM_DISABLE_USER')")
    public String disable(@PathVariable Long id, @RequestHeader(value = "Referer", required = false) String referer,
                          RedirectAttributes redirect) {
        return setEnabled(id, false, referer, redirect);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_USERS') and hasAuthority('PERM_DISABLE_USER')")
    public String enable(@PathVariable Long id, @RequestHeader(value = "Referer", required = false) String referer,
                         RedirectAttributes redirect) {
        return setEnabled(id, true, referer, redirect);
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('PAGE_USERS') and hasAuthority('PERM_RESET_PASSWORD')")
    public String resetPassword(@PathVariable Long id, @RequestParam String newPassword,
                                @RequestHeader(value = "Referer", required = false) String referer,
                                RedirectAttributes redirect) {
        try {
            User user = userService.resetPassword(id, newPassword, currentUserId());
            activityLogService.record(MODULE, "RESET_PASSWORD", "Reset password for user " + user.getUsername(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("user.passwordReset", user.getUsername()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "RESET_PASSWORD", "Failed to reset password for user #" + id + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return backTo(referer, id);
    }

    private String setEnabled(Long id, boolean enabled, String referer, RedirectAttributes redirect) {
        String action = enabled ? "ENABLE_USER" : "DISABLE_USER";
        try {
            User user = userService.setEnabled(id, enabled, currentUserId());
            activityLogService.record(MODULE, action, (enabled ? "Enabled" : "Disabled") + " user " + user.getUsername(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess",
                    messages.get(enabled ? "user.enabledMsg" : "user.disabledMsg", user.getUsername()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to change status of user #" + id + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return backTo(referer, id);
    }

    private String form(Model model, UserDto dto) {
        model.addAttribute("userDto", dto);
        model.addAttribute("allRoles", roleService.findAll());
        model.addAttribute("canAssignRoles", canAssignRoles());
        model.addAttribute("isSelf", dto.getId() != null && dto.getId().equals(currentUserId()));
        return "users/form";
    }

    private String invalid(Model model, UserDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    /** A business rule refused the form: show it next to its field, or as a toast. */
    private String rejected(Model model, UserDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            // Pass the args too: th:errors resolves the message again from its code.
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }

    /** Back to the list when the action came from it, otherwise to the user's page. */
    private static String backTo(String referer, Long id) {
        return referer != null && referer.matches(".*/users/?(\\?.*)?$")
                ? "redirect:/users" + referer.replaceFirst("^[^?]*", "")
                : "redirect:/users/" + id;
    }

    private static boolean canAssignRoles() {
        return AppUserPrincipal.currentHas("PERM_ASSIGN_ROLE");
    }

    private static Long currentUserId() {
        return AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
    }
}
