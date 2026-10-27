package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.JournalEntry;
import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.Ageing;
import com.ntaganira.heritier.iWarehouse.service.CreditControlService;
import com.ntaganira.heritier.iWarehouse.service.CustomerAccountService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.JournalService;
import com.ntaganira.heritier.iWarehouse.service.PostingService;
import com.ntaganira.heritier.iWarehouse.service.PrintTable;
import com.ntaganira.heritier.iWarehouse.service.SupplierAccountService;
import com.ntaganira.heritier.iWarehouse.service.TrialBalance;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : AccountingController.java
 * - Date      : 2026. 10. 09.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Journals (ACC-04): the list and a journal with its lines; the trial balance as at a day (ACC-11)
 *               with the inventory account checked against the stock valuation (AT-10); starting the ledger
 *               with the opening stock journal. Journals are posted by the business events, never here.
 *               PAGE_ACCOUNTING / PAGE_TRIAL_BALANCE + PERM_VIEW_ACCOUNTING; opening PERM_POST_OPENING_BALANCES.
 * </pre>
 */
@Controller
@RequestMapping("/accounting")
public class AccountingController {

    static final String MODULE = "Accounting";

    private final JournalService journalService;
    private final PostingService postingService;
    private final CustomerAccountService accountService;
    private final SupplierAccountService supplierAccountService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final ReportFiles reportFiles;
    private final CreditControlService creditControl;
    private final AgeingSheets ageingSheets;

    public AccountingController(JournalService journalService, PostingService postingService, CustomerAccountService accountService,
                                SupplierAccountService supplierAccountService, ActivityLogService activityLogService, Messages messages,
                                ReportFiles reportFiles, CreditControlService creditControl, AgeingSheets ageingSheets) {
        this.creditControl = creditControl;
        this.ageingSheets = ageingSheets;
        this.reportFiles = reportFiles;
        this.journalService = journalService;
        this.postingService = postingService;
        this.accountService = accountService;
        this.supplierAccountService = supplierAccountService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_ACCOUNTING')")
    public String home() {
        return "redirect:/accounting/journals";
    }

    @GetMapping("/journals")
    @PreAuthorize("hasAuthority('PAGE_ACCOUNTING') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String journals(@RequestParam(required = false) String search, @RequestParam(required = false) String source,
                           @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("journals", journalService.findPage(search, source, Paging.page(page), Paging.SIZE));
        model.addAttribute("sources", JournalSource.values());
        model.addAttribute("search", search);
        model.addAttribute("source", source);
        model.addAttribute("opening", journalService.openingStock().orElse(null));
        model.addAttribute("paginationQuery", QueryString.of("search", search, "source", source));
        return "journals/list";
    }

    @GetMapping("/journals/{id}")
    @PreAuthorize("hasAuthority('PAGE_ACCOUNTING') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String journal(@PathVariable UUID id, Model model) {
        JournalEntry journal = journalService.findById(id);
        List<JournalLine> lines = journalService.lines(id);
        model.addAttribute("journal", journal);
        model.addAttribute("lines", lines);
        model.addAttribute("debits", lines.stream().map(JournalLine::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("credits", lines.stream().map(JournalLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add));
        // A reversing journal and the one it reverses name each other (a revaluation reversed the next day)
        JournalEntry reverses = journal.getReversesId() == null ? null : journalService.findById(journal.getReversesId());
        List<JournalEntry> reversedBy = journalService.reversalsOf(id);
        Set<UUID> linked = new HashSet<>();
        linked.add(id);
        if (reverses != null) {
            linked.add(reverses.getId());
        }
        reversedBy.forEach(j -> linked.add(j.getId()));
        model.addAttribute("reverses", reverses);
        model.addAttribute("reversedBy", reversedBy);
        model.addAttribute("sameSource", journal.getSourceId() == null ? List.of()
                : journalService.forSource(journal.getSourceId(), JournalSource.values()).stream()
                .filter(j -> !linked.contains(j.getId())).toList());
        return "journals/view";
    }

    /** The aged receivables (ACC-09): every customer with a balance, by days past due. */
    @GetMapping("/receivables")
    @PreAuthorize("hasAuthority('PAGE_RECEIVABLES') and (hasAuthority('PERM_VIEW_ACCOUNTING') or hasAuthority('PERM_VIEW_CUSTOMER_ACCOUNT'))")
    public String receivables(@RequestParam(defaultValue = "0") int page, Model model) {
        CustomerAccountService.Receivables receivables = accountService.receivables();
        model.addAttribute("receivables", receivables);
        // Days sales outstanding and the share more than 90 days overdue (Annex D, F-02)
        model.addAttribute("kpis", creditControl.kpis(receivables));
        model.addAttribute("rows", Paging.of(receivables.customers(), Paging.page(page)));
        model.addAttribute("buckets", Ageing.Bucket.values());
        model.addAttribute("today", accountService.today());
        return "accounting/receivables";
    }

    /** The aged receivables in Excel or PDF (RPT-07). */
    @GetMapping("/receivables/export")
    @PreAuthorize("hasAuthority('PAGE_RECEIVABLES') and (hasAuthority('PERM_VIEW_ACCOUNTING') or hasAuthority('PERM_VIEW_CUSTOMER_ACCOUNT'))")
    public ResponseEntity<byte[]> receivablesExport(@RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        CustomerAccountService.Receivables receivables = accountService.receivables();
        LocalDate today = accountService.today();
        activityLogService.record(MODULE, "EXPORT_RECEIVABLES", "Exported the aged receivables to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "receivables-" + today, ageingSheets.receivables(messages.get("receivables.title"), receivables, today));
    }

    /** The aged payables (ACC-09): every supplier owed, per currency and by days past due (RWF as booked). */
    @GetMapping("/payables")
    @PreAuthorize("hasAuthority('PAGE_PAYABLES') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String payables(@RequestParam(defaultValue = "0") int page, Model model) {
        SupplierAccountService.PayablesReport payables = supplierAccountService.payables();
        model.addAttribute("payables", payables);
        model.addAttribute("rows", Paging.of(payables.suppliers(), Paging.page(page)));
        model.addAttribute("buckets", Ageing.Bucket.values());
        model.addAttribute("today", supplierAccountService.today());
        return "accounting/payables";
    }

    /** The aged payables in Excel or PDF (RPT-07): RWF as booked, and what is owed in each currency. */
    @GetMapping("/payables/export")
    @PreAuthorize("hasAuthority('PAGE_PAYABLES') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public ResponseEntity<byte[]> payablesExport(@RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        SupplierAccountService.PayablesReport payables = supplierAccountService.payables();
        LocalDate today = supplierAccountService.today();
        activityLogService.record(MODULE, "EXPORT_PAYABLES", "Exported the aged payables to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "payables-" + today, ageingSheets.payables(messages.get("payables.title"), payables, today));
    }

    @GetMapping("/trial-balance")
    @PreAuthorize("hasAuthority('PAGE_TRIAL_BALANCE') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public String trialBalance(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                               @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int dpage,
                               Model model) {
        LocalDate today = journalService.today();
        LocalDate day = asOf == null || asOf.isAfter(today) ? today : asOf;
        TrialBalance.Result tb = journalService.trialBalance(day);
        model.addAttribute("tb", tb);
        model.addAttribute("rows", Paging.of(tb.rows(), Paging.page(page)));
        model.addAttribute("asOf", day);
        model.addAttribute("today", today);
        // The stock valuation is today's: the check only means something as at today (AT-10)
        JournalService.InventoryCheck check = day.equals(today) ? journalService.inventoryCheck() : null;
        model.addAttribute("check", check);
        model.addAttribute("differences", check == null ? null : Paging.of(check.differences(), Paging.page(dpage)));
        model.addAttribute("opening", journalService.openingStock().orElse(null));
        String asOfParam = day.equals(today) ? null : day.toString();
        model.addAttribute("tbQuery", QueryString.of("asOf", asOfParam, "dpage", dpage > 0 ? String.valueOf(dpage) : null));
        model.addAttribute("diffQuery", QueryString.of("asOf", asOfParam, "page", page > 0 ? String.valueOf(page) : null));
        return "journals/trial-balance";
    }

    /** The trial balance as at a day, in Excel or PDF (ACC-11, RPT-07). */
    @GetMapping("/trial-balance/export")
    @PreAuthorize("hasAuthority('PAGE_TRIAL_BALANCE') and hasAuthority('PERM_VIEW_ACCOUNTING')")
    public ResponseEntity<byte[]> trialBalanceExport(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                                     @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        LocalDate today = journalService.today();
        LocalDate day = asOf == null || asOf.isAfter(today) ? today : asOf;
        TrialBalance.Result tb = journalService.trialBalance(day);
        Excel.Builder sheet = Excel.sheet(messages.get("tb.title"), messages.get("tb.title"),
                messages.get("fin.asOf", day.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))),
                messages.get("fin.code"), messages.get("fin.account"), messages.get("fin.type"), messages.get("fin.debit"), messages.get("fin.credit"));
        for (TrialBalance.Row r : tb.rows()) {
            sheet.row(r.account().getCode(), r.account().getName(), messages.get("account.type." + r.account().getType()),
                    r.getDebit().signum() == 0 ? null : r.getDebit(), r.getCredit().signum() == 0 ? null : r.getCredit());
        }
        sheet.bold(null, messages.get("fin.total"), null, tb.debit(), tb.credit());
        activityLogService.record(MODULE, "EXPORT_TRIAL_BALANCE", "Exported the trial balance as at " + day + " to " + format.label(),
                ActivityStatus.SUCCESS);
        return reportFiles.download(format, "trial-balance-" + day, sheet.build());
    }

    /** Starts the ledger: brings the inventory account to the stock value of this moment. Once only. */
    @PostMapping("/opening-stock")
    @PreAuthorize("hasAuthority('PAGE_TRIAL_BALANCE') and hasAuthority('PERM_POST_OPENING_BALANCES')")
    public String openingStock(RedirectAttributes redirect) {
        try {
            JournalEntry journal = postingService.openingStock();
            activityLogService.record(MODULE, "POST_OPENING_STOCK", "Started the ledger with " + journal.getNumber()
                    + ": opening stock of " + journal.getTotal().toPlainString() + " RWF", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("journal.opening.posted", journal.getNumber()));
            return "redirect:/accounting/journals/" + journal.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "POST_OPENING_STOCK", "Failed to post the opening stock: " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
            return "redirect:/accounting/trial-balance";
        }
    }
}
