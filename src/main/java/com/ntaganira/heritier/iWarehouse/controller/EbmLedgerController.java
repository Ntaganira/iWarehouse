package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.EbmLedgerService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : EbmLedgerController.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : EBM output against the sales ledger, monthly (U.13): the documents of the month and their receipts, what
 *               is signed, waiting or without a receipt, against the ledger's Sales, Sales Returns and VAT Output; in Excel
 *               or PDF. PAGE_EBM_LEDGER_REPORT + PERM_VIEW_ACCOUNTING.
 * </pre>
 */
@Controller
@RequestMapping("/reports/ebm-ledger")
public class EbmLedgerController {

    static final String MODULE = "Reports";
    private static final String VIEW = "hasAuthority('PAGE_EBM_LEDGER_REPORT') and hasAuthority('PERM_VIEW_ACCOUNTING')";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final EbmLedgerService ledgerService;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public EbmLedgerController(EbmLedgerService ledgerService, ReportFiles reportFiles, ActivityLogService activityLogService, Messages messages) {
        this.ledgerService = ledgerService;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String report(@RequestParam(required = false) String month, @RequestParam(defaultValue = "0") int page, Model model) {
        YearMonth m = month(month);
        EbmLedgerService.Report report = ledgerService.report(m);
        model.addAttribute("report", report);
        model.addAttribute("waiting", Paging.of(report.getWaiting(), Paging.page(page)));
        model.addAttribute("monthValue", m.toString());
        model.addAttribute("maxMonth", ledgerService.lastMonth().plusMonths(1).toString());
        model.addAttribute("paginationQuery", QueryString.of("month", m.toString()));
        return "reports/ebm-ledger";
    }

    @GetMapping("/export")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String month, @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        YearMonth m = month(month);
        EbmLedgerService.Report r = ledgerService.report(m);
        String title = messages.get("ebmLedger.title");
        Excel.Builder checks = Excel.sheet(messages.get("ebmLedger.checks"), title, m.format(MONTH), messages.get("ebmLedger.figure"),
                messages.get("dailyClose.value"));
        checks.row(messages.get("ebmLedger.invoices"), r.getInvoicesTotal());
        checks.row(messages.get("ebmLedger.invoicesSigned"), r.getInvoicesSigned());
        checks.row(messages.get("ebmLedger.creditNotes"), r.getCreditNotesTotal());
        checks.row(messages.get("ebmLedger.creditNotesSigned"), r.getCreditNotesSigned());
        checks.bold(messages.get("ebmLedger.documentsNet"), r.getDocumentsNet());
        checks.row(messages.get("ebmLedger.signedNet"), r.getSignedNet());
        checks.row(messages.get("ebmLedger.noReceiptNet"), r.getNoReceiptNet());
        checks.row(messages.get("ebmLedger.sales"), r.sales());
        checks.row(messages.get("ebmLedger.returns"), r.returns());
        checks.row(messages.get("ebmLedger.vat"), r.vat());
        checks.bold(messages.get("ebmLedger.ledgerNet"), r.getLedgerNet());
        checks.bold(messages.get("ebmLedger.difference"), r.getDocumentsLessLedger());
        Excel.Builder docs = Excel.sheet(messages.get("ebmLedger.documents"), title, m.format(MONTH), messages.get("ebmLedger.document"),
                messages.get("journal.date"), messages.get("invoice.customer"), messages.get("dailyClose.total"), messages.get("ebmLedger.receipt"));
        java.util.stream.Stream.concat(r.invoices().stream(), r.creditNotes().stream()).forEach(d -> docs.row(d.number(), d.date(), d.customer(),
                d.creditNote() ? d.total().negate() : d.total(), d.receipt() == null ? messages.get("ebmLedger.noReceipt")
                        : messages.get("ebm.status." + d.receipt().getStatus())));
        activityLogService.record(MODULE, "EXPORT_EBM_LEDGER", "Exported EBM against the ledger of " + m + " to " + format.label(), ActivityStatus.SUCCESS);
        return reportFiles.download(format, "ebm-ledger-" + m, checks.build(), docs.build());
    }

    private YearMonth month(String month) {
        YearMonth last = ledgerService.lastMonth();
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
