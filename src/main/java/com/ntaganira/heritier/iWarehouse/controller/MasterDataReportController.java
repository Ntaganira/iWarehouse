package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.DataChangeLog;
import com.ntaganira.heritier.iWarehouse.entity.MasterDataReview;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.MasterDataReviewService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : MasterDataReportController.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Master data changes (Board Paper 11 and Table 9): a month's changes to customers, suppliers, glass and
 *               prices, the accounts that look duplicated, the credit customers without their papers, the monthly review
 *               signed off and its export. PAGE_MASTER_DATA_REPORT + PERM_VIEW_MASTER_DATA_REPORT; review
 *               PERM_REVIEW_MASTER_DATA.
 * </pre>
 */
@Controller
@RequestMapping("/reports/master-data")
public class MasterDataReportController {

    private static final String VIEW = "hasAuthority('PAGE_MASTER_DATA_REPORT') and hasAuthority('PERM_VIEW_MASTER_DATA_REPORT')";
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final MasterDataReviewService reviewService;
    private final ActivityLogService activityLogService;
    private final ReportFiles reportFiles;
    private final Messages messages;

    public MasterDataReportController(MasterDataReviewService reviewService, ActivityLogService activityLogService, ReportFiles reportFiles,
                                      Messages messages) {
        this.reviewService = reviewService;
        this.activityLogService = activityLogService;
        this.reportFiles = reportFiles;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String report(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int rpage, Model model) {
        YearMonth m = month == null ? reviewService.thisMonth().minusMonths(1) : month;
        model.addAttribute("month", m);
        model.addAttribute("ended", m.isBefore(reviewService.thisMonth()));
        model.addAttribute("changes", reviewService.changes(m, Paging.page(page), Paging.SIZE));
        model.addAttribute("counts", reviewService.counts(m));
        model.addAttribute("review", reviewService.reviewOf(m).orElse(null));
        model.addAttribute("phones", reviewService.customersSharingPhone());
        model.addAttribute("accounts", reviewService.suppliersSharingAccount());
        model.addAttribute("withoutPapers", reviewService.creditWithoutPapers());
        model.addAttribute("reviews", reviewService.reviews(Paging.page(rpage), Paging.SIZE));
        model.addAttribute("query", QueryString.of("month", m.toString()));
        return "reports/master-data";
    }

    @PostMapping("/review")
    @PreAuthorize("hasAuthority('PAGE_MASTER_DATA_REPORT') and hasAuthority('PERM_REVIEW_MASTER_DATA')")
    public String review(@RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month, @RequestParam(required = false) String note,
                         RedirectAttributes redirect) {
        try {
            MasterDataReview review = reviewService.review(month, note);
            activityLogService.record(ReportController.MODULE, "REVIEW_MASTER_DATA", "Reviewed the master data changes of " + month + " ("
                    + review.getChanges() + " changes)" + (review.getNote() != null ? ": " + review.getNote() : ""), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("masterData.reviewed", month.toString()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(ReportController.MODULE, "REVIEW_MASTER_DATA", "Failed to review the master data changes of " + month
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/reports/master-data?month=" + month;
    }

    @GetMapping("/export")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                                         @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        YearMonth m = month == null ? reviewService.thisMonth().minusMonths(1) : month;
        Excel.Builder sheet = Excel.sheet(m("masterData.title"), m("masterData.title"), m("masterData.of", m.toString()),
                m("masterData.when"), m("masterData.record"), m("masterData.ref"), m("masterData.operation"), m("masterData.fields"),
                m("masterData.by"), m("masterData.reason"));
        for (DataChangeLog c : reviewService.allChanges(m)) {
            sheet.row(c.getServerTime().format(AT), m("masterData.type." + c.getEntityType()), c.getEntityRef() == null ? "" : c.getEntityRef(),
                    m("audit.op." + c.getOperation()), c.getChangedFields() == null ? "" : String.join(", ", c.getChangedFields()),
                    c.getUsername() == null ? "" : c.getUsername(), c.getReason() == null ? "" : c.getReason());
        }
        activityLogService.record(ReportController.MODULE, "EXPORT_MASTER_DATA", "Exported the master data changes of " + m + " to "
                + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "master-data-" + m, sheet.build());
    }

    private String m(String key, Object... args) {
        return messages.get(key, args);
    }
}
