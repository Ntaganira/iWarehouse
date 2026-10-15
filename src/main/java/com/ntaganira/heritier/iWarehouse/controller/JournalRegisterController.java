package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.JournalEntry;
import com.ntaganira.heritier.iWarehouse.entity.JournalRegisterCertification;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.JournalRegisterService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : JournalRegisterController.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The register of journal numbers (F-16): a month's journals by number, their total and any gap, its
 *               certification by the Director of Finance and the Internal Controller's sample, the months certified; in
 *               Excel or PDF. PAGE_JOURNAL_REGISTER + PERM_VIEW_ACCOUNTING; certifying PERM_CERTIFY_JOURNAL_REGISTER,
 *               sampling PERM_SAMPLE_JOURNALS.
 * </pre>
 */
@Controller
@RequestMapping("/accounting/journal-register")
public class JournalRegisterController {

    static final String MODULE = "Journal Register";
    private static final String VIEW = "hasAuthority('PAGE_JOURNAL_REGISTER') and hasAuthority('PERM_VIEW_ACCOUNTING')";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final JournalRegisterService registerService;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public JournalRegisterController(JournalRegisterService registerService, ReportFiles reportFiles, ActivityLogService activityLogService,
                                     Messages messages) {
        this.registerService = registerService;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String register(@RequestParam(required = false) String month, @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "0") int spage, Model model) {
        YearMonth m = month(month);
        JournalRegisterService.Register register = registerService.register(m);
        model.addAttribute("register", register);
        // The certifier does not sample their own register
        model.addAttribute("certifiedByMe", register.certification() != null && register.certification().getCertifiedById() != null
                && register.certification().getCertifiedById().equals(AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null)));
        model.addAttribute("journals", Paging.of(register.journals(), Paging.page(page)));
        model.addAttribute("certifications", registerService.certifications(Paging.page(spage), Paging.SIZE));
        model.addAttribute("monthValue", m.toString());
        model.addAttribute("maxMonth", YearMonth.now().toString());
        model.addAttribute("journalsQuery", QueryString.of("month", m.toString(), "spage", spage > 0 ? String.valueOf(spage) : null));
        model.addAttribute("certificationsQuery", QueryString.of("month", m.toString(), "page", page > 0 ? String.valueOf(page) : null));
        return "accounting/journal-register";
    }

    @PostMapping("/certify")
    @PreAuthorize("hasAuthority('PAGE_JOURNAL_REGISTER') and hasAuthority('PERM_CERTIFY_JOURNAL_REGISTER')")
    public String certify(@RequestParam String month, @RequestParam(required = false) String certifyNote, RedirectAttributes redirect) {
        YearMonth m = month(month);
        try {
            JournalRegisterCertification c = registerService.certify(m, certifyNote);
            activityLogService.record(MODULE, "APPROVE_JOURNAL_REGISTER", "Certified the journal register of " + m.format(MONTH) + ": "
                    + c.getJournals() + " journal(s) " + c.getFirstNumber() + " to " + c.getLastNumber()
                    + (c.getGaps() == null ? ", no gap" : ", gaps " + c.getGaps()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("journalRegister.certifiedMsg", m.format(MONTH)));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "APPROVE_JOURNAL_REGISTER", "Failed to certify the journal register of " + m.format(MONTH) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/accounting/journal-register?month=" + m;
    }

    @PostMapping("/{id}/sample")
    @PreAuthorize("hasAuthority('PAGE_JOURNAL_REGISTER') and hasAuthority('PERM_SAMPLE_JOURNALS')")
    public String sample(@PathVariable UUID id, @RequestParam(required = false) String sampleNote, RedirectAttributes redirect) {
        String month = "";
        try {
            JournalRegisterCertification c = registerService.sample(id, sampleNote);
            month = YearMonth.from(c.getPeriodEnd()).toString();
            activityLogService.record(MODULE, "REVIEW_JOURNAL_REGISTER", "Sampled the journal register of " + YearMonth.from(c.getPeriodEnd()).format(MONTH)
                    + ": " + c.getSampleNote(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("journalRegister.sampledMsg", YearMonth.from(c.getPeriodEnd()).format(MONTH)));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "REVIEW_JOURNAL_REGISTER", "Failed to record a journal register sample: " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/accounting/journal-register" + (month.isEmpty() ? "" : "?month=" + month);
    }

    @GetMapping("/export")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String month, @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        YearMonth m = month(month);
        JournalRegisterService.Register r = registerService.register(m);
        Excel.Builder sheet = Excel.sheet(messages.get("journalRegister.title"), messages.get("journalRegister.title"),
                messages.get("journalRegister.exportSubtitle", m.format(MONTH), r.journals().size(),
                        r.gaps().isEmpty() ? messages.get("journalRegister.noGap") : String.join(", ", r.gaps())),
                messages.get("journal.number"), messages.get("journal.date"), messages.get("journal.source"), messages.get("journal.description"),
                messages.get("journal.total"), messages.get("journal.postedBy"));
        for (JournalEntry e : r.journals()) {
            sheet.row(e.getNumber(), e.getEntryDate(), messages.get("journal.source." + e.getSourceType()), e.getDescription(), e.getTotal(),
                    e.getUsername());
        }
        sheet.bold(messages.get("dailyClose.total"), null, null, null, r.total(), null);
        activityLogService.record(MODULE, "EXPORT_JOURNAL_REGISTER", "Exported the journal register of " + m + " to " + format.label(),
                ActivityStatus.SUCCESS);
        return reportFiles.download(format, "journal-register-" + m, sheet.build());
    }

    private YearMonth month(String month) {
        YearMonth last = registerService.lastMonth();
        if (month == null || month.isBlank()) {
            return last;
        }
        try {
            YearMonth m = YearMonth.parse(month);
            return m.isAfter(last.plusMonths(1)) ? last.plusMonths(1) : m;
        } catch (DateTimeParseException e) {
            return last;
        }
    }
}
