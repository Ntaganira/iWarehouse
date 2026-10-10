package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.ApiDevice;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.DeviceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : DeviceController.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Phones signed in to the mobile POS (NFR-10): who, which phone, when it was last seen; revoking one (a lost
 *               phone, a driver who left) refuses its token at once, with a reason. PAGE_DEVICES + PERM_VIEW_DEVICES;
 *               revoking needs PERM_REVOKE_DEVICE.
 * </pre>
 */
@Controller
@RequestMapping("/devices")
public class DeviceController {

    static final String MODULE = "Mobile POS";
    private static final int REASON_MAX = 255;

    private final DeviceService deviceService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public DeviceController(DeviceService deviceService, DataChangeService dataChangeService, ActivityLogService activityLogService,
                            Messages messages) {
        this.deviceService = deviceService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_DEVICES') and hasAuthority('PERM_VIEW_DEVICES')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("devices", deviceService.findPage(search, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("live", deviceService.liveCount());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "devices/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_DEVICES') and hasAuthority('PERM_VIEW_DEVICES')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "details") String tab, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        String open = List.of("details", "history").contains(tab) ? tab : "details";
        model.addAttribute("device", deviceService.findDetailed(id));
        model.addAttribute("history", dataChangeService.history("ApiDevice", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "devices/view";
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('PAGE_DEVICES') and hasAuthority('PERM_REVOKE_DEVICE')")
    public String revoke(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/devices/" + id;
        }
        try {
            ApiDevice device = AuditContext.withReason(reason.trim(), () -> deviceService.revoke(id, reason));
            activityLogService.record(MODULE, "REVOKE_DEVICE", "Revoked the phone \"" + device.getName() + "\" of " + device.getUsername()
                    + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("device.revokedMsg", device.getName(), device.getUsername()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "REVOKE_DEVICE", "Failed to revoke a phone: " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/devices/" + id;
    }
}
