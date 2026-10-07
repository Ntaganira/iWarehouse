package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.repository.ActivityLogRepository;
import com.ntaganira.heritier.iWarehouse.repository.DataChangeLogRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Dashboard. Business KPIs (sales by channel, stock value, float...) are added module by module (RPT-01). */
@Controller
public class DashboardController {

    private final ActivityLogRepository activityRepo;
    private final DataChangeLogRepository changeRepo;

    public DashboardController(ActivityLogRepository activityRepo, DataChangeLogRepository changeRepo) {
        this.activityRepo = activityRepo;
        this.changeRepo = changeRepo;
    }

    @GetMapping({"/", "/dashboard"})
    @PreAuthorize("hasAuthority('PERM_VIEW_DASHBOARD')")
    public String dashboard(Model model) {
        LocalDateTime today = LocalDate.now().atStartOfDay();
        model.addAttribute("activityToday", activityRepo.countByCreatedAtAfter(today));
        model.addAttribute("failedToday", activityRepo.countByStatusAndCreatedAtAfter(ActivityStatus.FAILED, today));
        model.addAttribute("changesToday", changeRepo.countByServerTimeAfter(today));
        return "dashboard";
    }
}
