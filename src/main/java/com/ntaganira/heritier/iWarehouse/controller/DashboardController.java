package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.BusinessDashboardService;
import com.ntaganira.heritier.iWarehouse.service.DashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Dashboard. Everyone sees their latest actions; the owner's figures (sales, margin, stock value, cash, what waits: RPT-01)
 * need PERM_VIEW_BUSINESS_DASHBOARD, the stock value PERM_VIEW_STOCK_COST too.
 */
@Controller
public class DashboardController {

    private final DashboardService dashboardService;
    private final BusinessDashboardService businessService;

    public DashboardController(DashboardService dashboardService, BusinessDashboardService businessService) {
        this.dashboardService = dashboardService;
        this.businessService = businessService;
    }

    @GetMapping({"/", "/dashboard"})
    @PreAuthorize("hasAuthority('PERM_VIEW_DASHBOARD')")
    public String dashboard(Authentication auth, Model model) {
        // Everyone sees their own latest actions. Everyone's actions and the record changes
        // need the same rights as the Activity Logs and Data Changes screens.
        boolean allActivity = has(auth, "PAGE_ACTIVITY_LOGS") && has(auth, "PERM_VIEW_ACTIVITY_LOG");
        boolean showChanges = has(auth, "PAGE_DATA_CHANGES") && has(auth, "PERM_VIEW_DATA_CHANGES");
        Long userId = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);

        model.addAttribute("dash", dashboardService.load(userId, allActivity, showChanges));
        model.addAttribute("allActivity", allActivity);
        model.addAttribute("showChanges", showChanges);
        if (has(auth, "PERM_VIEW_BUSINESS_DASHBOARD")) {
            model.addAttribute("biz", businessService.load());
        }
        return "dashboard";
    }

    private static boolean has(Authentication auth, String authority) {
        return auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
