package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.NotificationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : NotificationController.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The signed-in user's notifications (RPT-06), as iVura's: all or unread, opening one (marked read, then
 *               its page), marking all read. The header bell shows the latest. PAGE_NOTIFICATIONS + PERM_VIEW_NOTIFICATIONS.
 * </pre>
 */
@Controller
@RequestMapping("/notifications")
public class NotificationController {

    static final String MODULE = "Notifications";
    private static final String AUTH = "hasAuthority('PAGE_NOTIFICATIONS') and hasAuthority('PERM_VIEW_NOTIFICATIONS')";

    private final NotificationService notificationService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public NotificationController(NotificationService notificationService, ActivityLogService activityLogService, Messages messages) {
        this.notificationService = notificationService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(AUTH)
    public String list(@RequestParam(required = false) String show, @RequestParam(defaultValue = "0") int page, Model model) {
        boolean unread = "unread".equals(show);
        model.addAttribute("notifications", notificationService.page(unread, Paging.page(page), Paging.SIZE));
        model.addAttribute("show", unread ? "unread" : "all");
        model.addAttribute("unreadCount", notificationService.unreadCount());
        model.addAttribute("query", unread ? "show=unread" : "");
        return "notifications/list";
    }

    /** Marks it read and goes to its page (the list when it has none). */
    @PostMapping("/{id}/open")
    @PreAuthorize(AUTH)
    public String open(@PathVariable Long id) {
        String link = notificationService.open(id);
        return "redirect:" + (link == null || !link.startsWith("/") || link.startsWith("//") ? "/notifications" : link);
    }

    @PostMapping("/read-all")
    @PreAuthorize(AUTH)
    public String readAll(RedirectAttributes redirect) {
        int count = notificationService.readAll();
        activityLogService.record(MODULE, "READ_NOTIFICATIONS", "Marked " + count + " notification(s) read", ActivityStatus.SUCCESS);
        redirect.addFlashAttribute("flashSuccess", messages.get("notification.allRead", count));
        return "redirect:/notifications";
    }
}
