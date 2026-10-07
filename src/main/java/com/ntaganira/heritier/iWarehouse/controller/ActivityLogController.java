package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.entity.ActivityLog;
import com.ntaganira.heritier.iWarehouse.repository.UserRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@Controller
@RequestMapping("/activity")
public class ActivityLogController {

    private static final int PAGE_SIZE = 10;

    private final ActivityLogService activityLogService;
    private final UserRepository userRepo;

    public ActivityLogController(ActivityLogService activityLogService, UserRepository userRepo) {
        this.activityLogService = activityLogService;
        this.userRepo = userRepo;
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('PAGE_MY_ACTIVITY')")
    public String myActivity(@RequestParam(defaultValue = "0") int page,
                             @RequestParam(required = false) String module,
                             @RequestParam(required = false) String action,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                             Model model) {
        Long userId = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        if (userId == null) {
            return "redirect:/login";
        }
        Page<ActivityLog> logs = activityLogService.findPage(userId, module, action,
                status, from, to, Math.max(page, 0), PAGE_SIZE);
        populate(model, logs, null, module, action, status, from, to);
        return "activity/my";
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_ACTIVITY_LOGS') and hasAuthority('PERM_VIEW_ACTIVITY_LOG')")
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Long userId,
                       @RequestParam(required = false) String module,
                       @RequestParam(required = false) String action,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       Model model) {
        Page<ActivityLog> logs = activityLogService.findPage(userId, module, action,
                status, from, to, Math.max(page, 0), PAGE_SIZE);
        model.addAttribute("users", userRepo.findAll(Sort.by(Sort.Direction.ASC, "fullName")));
        model.addAttribute("selectedUserId", userId);
        populate(model, logs, userId, module, action, status, from, to);
        return "activity/list";
    }

    private void populate(Model model, Page<ActivityLog> logs, Long userId, String module, String action,
                          String status, LocalDate from, LocalDate to) {
        model.addAttribute("logs", logs);
        model.addAttribute("modules", activityLogService.findModules());
        model.addAttribute("actions", activityLogService.findActions());
        model.addAttribute("module", module);
        model.addAttribute("action", action);
        model.addAttribute("status", status);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("paginationQuery", buildQuery(userId, module, action, status, from, to));
    }

    private String buildQuery(Long userId, String module, String action,
                              String status, LocalDate from, LocalDate to) {
        StringBuilder q = new StringBuilder();
        if (userId != null) {
            q.append("userId=").append(userId);
        }
        if (StringUtils.hasText(module)) {
            if (q.length() > 0) q.append('&');
            q.append("module=").append(URLEncoder.encode(module.trim(), StandardCharsets.UTF_8));
        }
        if (StringUtils.hasText(action)) {
            if (q.length() > 0) q.append('&');
            q.append("action=").append(URLEncoder.encode(action.trim(), StandardCharsets.UTF_8));
        }
        if (StringUtils.hasText(status)) {
            if (q.length() > 0) q.append('&');
            q.append("status=").append(status.trim());
        }
        if (from != null) {
            if (q.length() > 0) q.append('&');
            q.append("from=").append(from);
        }
        if (to != null) {
            if (q.length() > 0) q.append('&');
            q.append("to=").append(to);
        }
        return q.toString();
    }

}
