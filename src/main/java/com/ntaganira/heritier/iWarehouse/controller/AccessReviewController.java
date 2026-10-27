package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.AccessReview;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.AccessReviewService;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : AccessReviewController.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The quarterly access review (Board Paper Table 8): every user, their roles, their rights and last
 *               sign-in, the past reviews, an export for the file, and recording a review (PERM_REVIEW_ACCESS).
 * </pre>
 */
@Controller
@RequestMapping("/users/access-review")
public class AccessReviewController {

    private static final String MODULE = "Users";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final AccessReviewService reviewService;
    private final ActivityLogService activityLogService;
    private final ReportFiles reportFiles;
    private final Messages messages;

    public AccessReviewController(AccessReviewService reviewService, ActivityLogService activityLogService, ReportFiles reportFiles,
                                  Messages messages) {
        this.reviewService = reviewService;
        this.activityLogService = activityLogService;
        this.reportFiles = reportFiles;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_USERS') and hasAuthority('PERM_VIEW_USER')")
    public String view(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int spage, Model model) {
        model.addAttribute("users", Paging.of(reviewService.users(), Paging.page(page)));
        model.addAttribute("reviews", reviewService.reviews(Paging.page(spage), Paging.SIZE));
        model.addAttribute("latest", reviewService.latest().orElse(null));
        model.addAttribute("due", reviewService.due());
        model.addAttribute("everyDays", AccessReviewService.EVERY_DAYS);
        model.addAttribute("page", page);
        model.addAttribute("spage", spage);
        return "users/access-review";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PAGE_USERS') and hasAuthority('PERM_REVIEW_ACCESS')")
    public String record(@RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            AccessReview review = reviewService.record(note);
            activityLogService.record(MODULE, "CREATE_ACCESS_REVIEW", "Recorded the access review of " + review.getUsersCount() + " users ("
                    + review.getEnabledCount() + " enabled): " + review.getNote(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("accessReview.recorded"));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_ACCESS_REVIEW", "Failed to record the access review: " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/users/access-review";
    }

    /** Every user with their roles, rights and last sign-in, for the review's file (RPT-07). */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('PAGE_USERS') and hasAuthority('PERM_VIEW_USER')")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        List<AccessReviewService.UserAccess> users = reviewService.users();
        LocalDate today = reviewService.today();
        Excel.Builder sheet = Excel.sheet(messages.get("accessReview.title"), messages.get("accessReview.title"),
                messages.get("fin.asOf", today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))),
                messages.get("user.username"), messages.get("user.fullName"), messages.get("common.status"), messages.get("user.roles"),
                messages.get("accessReview.rights"), messages.get("accessReview.lastSignIn"));
        for (AccessReviewService.UserAccess u : users) {
            sheet.row(u.user().getUsername(), u.user().getFullName(),
                    u.user().isEnabled() ? messages.get("common.active") : messages.get("common.inactive"),
                    String.join(", ", u.roles()), u.rights(), u.lastSignIn() == null ? "" : u.lastSignIn().format(DATE_TIME));
        }
        activityLogService.record(MODULE, "EXPORT_ACCESS_REVIEW", "Exported the users and their rights to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "access-review-" + today, sheet.build());
    }
}
