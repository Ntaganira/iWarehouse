package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.DailyClose;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.AttachmentService;
import com.ntaganira.heritier.iWarehouse.service.DailyCloseService;
import com.ntaganira.heritier.iWarehouse.service.DailyCloses;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : DailyCloseController.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The daily close (F-12): the closes with the days still to close, the end-of-day sales report of a day
 *               (by channel and method, salesperson, customer, collections, the credit list, the tills and banking, the
 *               EBM receipts, the glass on vehicles) with its close: prepare it, sign it (another person), test it (the
 *               Internal Controller); in Excel or PDF. PAGE_DAILY_CLOSES + PERM_VIEW_DAILY_CLOSE; preparing
 *               PERM_PREPARE_DAILY_CLOSE, signing PERM_SIGN_DAILY_CLOSE, testing PERM_TEST_DAILY_CLOSE.
 * </pre>
 */
@Controller
@RequestMapping("/daily-close")
public class DailyCloseController {

    static final String MODULE = "Daily Close";
    private static final String VIEW = "hasAuthority('PAGE_DAILY_CLOSES') and hasAuthority('PERM_VIEW_DAILY_CLOSE')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final List<String> TABS = List.of("channels", "salespeople", "customers", "collections", "credit", "tills", "ebm",
            "vans", "documents", "history");

    private final DailyCloseService closeService;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public DailyCloseController(DailyCloseService closeService, AttachmentService attachmentService, DataChangeService dataChangeService,
                                ReportFiles reportFiles, ActivityLogService activityLogService, Messages messages) {
        this.closeService = closeService;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String list(@RequestParam(required = false) String filter, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("closes", closeService.findPage(filter, Paging.page(page), Paging.SIZE));
        model.addAttribute("filters", List.of("PREPARED", "SIGNED", "untested"));
        model.addAttribute("filter", filter);
        model.addAttribute("toClose", closeService.daysToClose());
        model.addAttribute("today", closeService.today());
        model.addAttribute("paginationQuery", QueryString.of("filter", filter));
        return "daily-close/list";
    }

    /** A day's end-of-day report and its close. */
    @GetMapping("/{day}")
    @PreAuthorize(VIEW)
    public String day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day, @RequestParam(defaultValue = "channels") String tab,
                      @RequestParam(defaultValue = "0") int page, Model model) {
        LocalDate today = closeService.today();
        if (day.isAfter(today)) {
            return "redirect:/daily-close/" + today;
        }
        String open = TABS.contains(tab) ? tab : "channels";
        DailyCloseService.Report report = closeService.report(day);
        Optional<DailyClose> close = closeService.findByDay(day);
        model.addAttribute("day", day);
        model.addAttribute("today", today);
        model.addAttribute("report", report);
        model.addAttribute("close", close.orElse(null));
        model.addAttribute("preparer", close.map(DailyCloseService::isPreparer).orElse(false));
        // A third person tests a signed close: neither who prepared it nor who signed it
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        model.addAttribute("mayTest", close.map(c -> c.isSigned() && !c.isTested() && me != null && !me.equals(c.getPreparedById())
                && !me.equals(c.getSignedById())).orElse(false));
        model.addAttribute("salespeople", Paging.of(report.salespeople(), Paging.pageOf("salespeople", open, page)));
        model.addAttribute("customers", Paging.of(report.customers(), Paging.pageOf("customers", open, page)));
        model.addAttribute("collections", Paging.of(report.collections(), Paging.pageOf("collections", open, page)));
        model.addAttribute("credits", Paging.of(report.credits(), Paging.pageOf("credit", open, page)));
        model.addAttribute("tills", Paging.of(report.tills(), Paging.pageOf("tills", open, page)));
        model.addAttribute("receipts", Paging.of(report.receipts(), Paging.pageOf("ebm", open, page)));
        model.addAttribute("vans", Paging.of(report.vans(), Paging.pageOf("vans", open, page)));
        close.ifPresent(c -> {
            model.addAttribute("documents", attachmentService.page(AttachmentOwner.DAILY_CLOSE, c.getId(), Paging.pageOf("documents", open, page), Paging.SIZE));
            model.addAttribute("history", dataChangeService.history("DailyClose", c.getId().toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        });
        model.addAttribute("tab", open);
        return "daily-close/day";
    }

    @PostMapping("/{day}/prepare")
    @PreAuthorize("hasAuthority('PAGE_DAILY_CLOSES') and hasAuthority('PERM_PREPARE_DAILY_CLOSE')")
    public String prepare(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                          @RequestParam(required = false) BigDecimal momoStatement, @RequestParam(required = false) String varianceNote,
                          RedirectAttributes redirect) {
        try {
            DailyClose c = closeService.prepare(day, momoStatement, varianceNote);
            activityLogService.record(MODULE, "CREATE_DAILY_CLOSE", "Prepared the close of " + day.format(DAY) + " (" + c.getNumber() + "): sales "
                    + c.getSalesTotal().toPlainString() + " RWF, cash variance " + c.getCashVariance().toPlainString() + ", MoMo variance "
                    + c.getMomoVariance().toPlainString() + (c.getVarianceNote() == null ? "" : " (" + c.getVarianceNote() + ")"), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute(c.isVariance() ? "flashWarning" : "flashSuccess",
                    messages.get(c.isVariance() ? "dailyClose.preparedVariance" : "dailyClose.prepared", day.format(DAY)));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_DAILY_CLOSE", "Failed to prepare the close of " + day.format(DAY) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
            redirect.addFlashAttribute("momoStatement", momoStatement == null ? null : momoStatement.toPlainString());
            redirect.addFlashAttribute("varianceNote", varianceNote);
        }
        return "redirect:/daily-close/" + day;
    }

    @PostMapping("/close/{id}/sign")
    @PreAuthorize("hasAuthority('PAGE_DAILY_CLOSES') and hasAuthority('PERM_SIGN_DAILY_CLOSE')")
    public String sign(@PathVariable UUID id, @RequestParam(required = false) String signNote, RedirectAttributes redirect) {
        DailyClose close = closeService.findById(id);
        try {
            closeService.sign(id, signNote);
            activityLogService.record(MODULE, "APPROVE_DAILY_CLOSE", "Signed the close of " + close.getBusinessDay().format(DAY) + " ("
                    + close.getNumber() + ")" + (close.getSignNote() == null ? "" : ": " + close.getSignNote()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("dailyClose.signedMsg", close.getBusinessDay().format(DAY)));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "APPROVE_DAILY_CLOSE", "Failed to sign the close of " + close.getBusinessDay().format(DAY) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/daily-close/" + close.getBusinessDay();
    }

    @PostMapping("/close/{id}/test")
    @PreAuthorize("hasAuthority('PAGE_DAILY_CLOSES') and hasAuthority('PERM_TEST_DAILY_CLOSE')")
    public String test(@PathVariable UUID id, @RequestParam(required = false) String testNote, RedirectAttributes redirect) {
        DailyClose close = closeService.findById(id);
        try {
            closeService.test(id, testNote);
            activityLogService.record(MODULE, "REVIEW_DAILY_CLOSE", "Tested the close of " + close.getBusinessDay().format(DAY) + ": "
                    + close.getTestNote(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("dailyClose.testedMsg", close.getBusinessDay().format(DAY)));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "REVIEW_DAILY_CLOSE", "Failed to test the close of " + close.getBusinessDay().format(DAY) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/daily-close/" + close.getBusinessDay();
    }

    /** The day's report in Excel or PDF (RPT-07): the figures, by channel, by salesperson, by customer, the credit list, the tills, EBM. */
    @GetMapping("/{day}/export")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> export(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
                                         @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        DailyCloseService.Report r = closeService.report(day);
        String title = messages.get("dailyClose.exportTitle", day.format(DAY));
        Excel.Builder figures = Excel.sheet(messages.get("dailyClose.figures"), title, messages.get("dailyClose.subtitle"),
                messages.get("dailyClose.figure"), messages.get("dailyClose.value"));
        figures.row(messages.get("dailyClose.invoices"), r.invoices().size());
        figures.bold(messages.get("dailyClose.salesTotal"), r.getSalesTotal());
        figures.row(messages.get("dailyClose.collections"), r.getCollectionsTotal());
        figures.row(messages.get("dailyClose.refunds"), r.getRefundsTotal());
        figures.row(messages.get("dailyClose.creditGranted"), r.getCreditGranted());
        figures.row(messages.get("dailyClose.cashExpected"), r.getCashExpected());
        figures.row(messages.get("dailyClose.cashCounted"), r.getCashCounted());
        figures.bold(messages.get("dailyClose.cashVariance"), r.getCashVariance());
        figures.row(messages.get("dailyClose.cashToBank"), r.getCashToBank());
        figures.row(messages.get("dailyClose.banked"), r.getBanked());
        figures.row(messages.get("dailyClose.momoReceived"), r.momoReceived());
        figures.row(messages.get("dailyClose.ebmReceipts"), r.getEbmCount());
        figures.row(messages.get("dailyClose.ebmFirst"), r.getEbmFirst());
        figures.row(messages.get("dailyClose.ebmLast"), r.getEbmLast());
        figures.row(messages.get("dailyClose.ebmUnsigned"), r.getEbmUnsigned());
        figures.row(messages.get("dailyClose.notesReleased"), r.notesReleased());
        Excel.Builder channels = Excel.sheet(messages.get("dailyClose.tab.channels"), title, messages.get("dailyClose.tab.channels"),
                messages.get("dailyClose.channel"), messages.get("dailyClose.invoices"), messages.get("dailyClose.cash"), messages.get("dailyClose.momo"),
                messages.get("dailyClose.other"), messages.get("dailyClose.credit"), messages.get("dailyClose.total"));
        for (DailyCloses.ChannelRow c : r.channels()) {
            channels.row(messages.get("order.channel." + c.channel()), c.invoices(), c.cash(), c.momo(), c.other(), c.credit(), c.getTotal());
        }
        DailyCloses.ChannelRow t = r.totals();
        channels.bold(messages.get("dailyClose.total"), t.invoices(), t.cash(), t.momo(), t.other(), t.credit(), t.getTotal());
        Excel.Builder people = Excel.sheet(messages.get("dailyClose.tab.salespeople"), title, messages.get("dailyClose.tab.salespeople"),
                messages.get("dailyClose.salesperson"), messages.get("dailyClose.invoices"), messages.get("dailyClose.total"));
        r.salespeople().forEach(p -> people.row(p.name(), p.invoices(), p.total()));
        Excel.Builder customers = Excel.sheet(messages.get("dailyClose.tab.customers"), title, messages.get("dailyClose.tab.customers"),
                messages.get("invoice.customer"), messages.get("dailyClose.invoices"), messages.get("dailyClose.total"));
        r.customers().forEach(p -> customers.row(p.name(), p.invoices(), p.total()));
        Excel.Builder credit = Excel.sheet(messages.get("dailyClose.tab.credit"), title, messages.get("dailyClose.creditListSubtitle"),
                messages.get("invoice.number"), messages.get("invoice.customer"), messages.get("dailyClose.credit"));
        r.credits().forEach(c -> credit.row(c.invoice().getNumber(), c.invoice().getCustomer().getName(), c.amount()));
        Excel.Builder tills = Excel.sheet(messages.get("dailyClose.tab.tills"), title, messages.get("dailyClose.tab.tills"),
                messages.get("till.number"), messages.get("till.cashier"), messages.get("dailyClose.cashExpected"), messages.get("dailyClose.cashCounted"),
                messages.get("dailyClose.cashVariance"), messages.get("dailyClose.cashToBank"), messages.get("till.verifiedBy"));
        r.tills().forEach(s -> tills.row(s.getNumber(), s.getCashierUsername(), s.getExpectedCash(), s.getCountedCash(), s.getDifference(),
                s.getCashToBank(), s.getVerifiedBy()));
        activityLogService.record(MODULE, "EXPORT_DAILY_CLOSE", "Exported the end-of-day report of " + day.format(DAY) + " to " + format.label(),
                ActivityStatus.SUCCESS);
        return reportFiles.download(format, "daily-close-" + day, figures.build(), channels.build(), people.build(), customers.build(),
                credit.build(), tills.build());
    }
}
