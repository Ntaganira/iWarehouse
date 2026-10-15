package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.CustomerReminder;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.CreditControlService;
import com.ntaganira.heritier.iWarehouse.service.CustomerService;
import com.ntaganira.heritier.iWarehouse.service.Excel;
import com.ntaganira.heritier.iWarehouse.service.PdfService;
import com.ntaganira.heritier.iWarehouse.service.ReceivableMovement;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : CreditControlController.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Following up receivables (BP 5.1, Table 9): the monthly movement of receivables (Excel or PDF), payment
 *               reminders (customers overdue with the level due, the letters issued, each letter on A4) and a customer's
 *               statement for a period (A4 PDF with the letterhead). PAGE_RECEIVABLES + PERM_VIEW_ACCOUNTING or
 *               PERM_VIEW_CUSTOMER_ACCOUNT; issuing reminders PERM_ISSUE_REMINDER; statements PAGE_CUSTOMERS +
 *               PERM_VIEW_CUSTOMER_ACCOUNT.
 * </pre>
 */
@Controller
public class CreditControlController {

    static final String MODULE = "Credit Control";
    private static final String VIEW = "hasAuthority('PAGE_RECEIVABLES') and (hasAuthority('PERM_VIEW_ACCOUNTING') or hasAuthority('PERM_VIEW_CUSTOMER_ACCOUNT'))";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private final CreditControlService creditControl;
    private final CustomerService customerService;
    private final PdfService pdfService;
    private final ReportFiles reportFiles;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public CreditControlController(CreditControlService creditControl, CustomerService customerService, PdfService pdfService,
                                   ReportFiles reportFiles, ActivityLogService activityLogService, Messages messages) {
        this.creditControl = creditControl;
        this.customerService = customerService;
        this.pdfService = pdfService;
        this.reportFiles = reportFiles;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    // ---------------------------------------------------------------- the movement of receivables (Q.06)

    @GetMapping("/accounting/receivables/movement")
    @PreAuthorize(VIEW)
    public String movement(@RequestParam(required = false) String month, @RequestParam(defaultValue = "0") int page, Model model) {
        YearMonth m = month(month);
        CreditControlService.Movement movement = creditControl.movement(m);
        model.addAttribute("movement", movement);
        model.addAttribute("rows", Paging.of(movement.rows(), Paging.page(page)));
        model.addAttribute("monthValue", m.toString());
        model.addAttribute("maxMonth", YearMonth.from(creditControl.today()).toString());
        model.addAttribute("paginationQuery", QueryString.of("month", m.toString()));
        return "accounting/receivable-movement";
    }

    @GetMapping("/accounting/receivables/movement/export")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> movementExport(@RequestParam(required = false) String month,
                                                 @RequestParam(defaultValue = "XLSX") ReportFiles.Format format) {
        YearMonth m = month(month);
        CreditControlService.Movement movement = creditControl.movement(m);
        Excel.Builder sheet = Excel.sheet(messages.get("movement.title"), messages.get("movement.title"), m.format(MONTH),
                messages.get("invoice.customer"), messages.get("movement.opening"), messages.get("movement.newCredit"),
                messages.get("movement.collections"), messages.get("movement.creditNotes"), messages.get("movement.writeOffs"),
                messages.get("movement.other"), messages.get("movement.closing"));
        for (CreditControlService.MovementRow r : movement.rows()) {
            ReceivableMovement.Row x = r.row();
            sheet.row(r.customer().getName(), x.opening(), x.newCredit(), x.collections(), x.creditNotes(), x.writeOffs(), x.other(), x.getClosing());
        }
        ReceivableMovement.Row t = movement.total();
        sheet.bold(messages.get("dailyClose.total"), t.opening(), t.newCredit(), t.collections(), t.creditNotes(), t.writeOffs(), t.other(), t.getClosing());
        activityLogService.record(MODULE, "EXPORT_RECEIVABLE_MOVEMENT", "Exported the movement of receivables of " + m + " to " + format.label(),
                ActivityStatus.SUCCESS);
        return reportFiles.download(format, "receivables-movement-" + m, sheet.build());
    }

    // ---------------------------------------------------------------- reminders (Q.04)

    @GetMapping("/accounting/receivables/reminders")
    @PreAuthorize(VIEW)
    public String reminders(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int spage, Model model) {
        model.addAttribute("due", Paging.of(creditControl.remindersDue(), Paging.page(page)));
        model.addAttribute("issued", creditControl.reminders(null, Paging.page(spage), Paging.SIZE));
        model.addAttribute("dueQuery", QueryString.of("spage", spage > 0 ? String.valueOf(spage) : null));
        model.addAttribute("issuedQuery", QueryString.of("page", page > 0 ? String.valueOf(page) : null));
        return "accounting/reminders";
    }

    @PostMapping("/accounting/receivables/reminders")
    @PreAuthorize("hasAuthority('PAGE_RECEIVABLES') and hasAuthority('PERM_ISSUE_REMINDER')")
    public String issue(@RequestParam UUID customerId, RedirectAttributes redirect) {
        try {
            CustomerReminder r = creditControl.issue(customerId);
            activityLogService.record(MODULE, "CREATE_REMINDER", "Issued reminder " + r.getNumber() + " (level " + r.getLevel() + ") to "
                    + r.getCustomer().getName() + ": " + r.getOverdue().toPlainString() + " RWF overdue", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("reminder.issued", r.getNumber(), r.getCustomer().getName()));
            redirect.addFlashAttribute("issuedId", r.getId());
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_REMINDER", "Failed to issue a reminder to customer " + customerId + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/accounting/receivables/reminders";
    }

    /** The reminder letter on A4 with the letterhead. */
    @GetMapping("/accounting/receivables/reminders/{id}/pdf")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> reminderPdf(@PathVariable UUID id) {
        CustomerReminder r = creditControl.findReminder(id);
        Map<String, Object> model = reportFiles.letterhead();
        model.put("reminder", r);
        model.put("customer", r.getCustomer());
        model.put("statement", creditControl.statement(r.getCustomer(), r.getAsOf().minusMonths(3).withDayOfMonth(1), r.getAsOf()));
        byte[] body = pdfService.render("accounting/reminder-pdf", model);
        activityLogService.record(MODULE, "EXPORT_REMINDER", "Downloaded reminder " + r.getNumber() + " to PDF", ActivityStatus.SUCCESS);
        return ReportFiles.file(r.getNumber() + ".pdf", MediaType.APPLICATION_PDF, body);
    }

    // ---------------------------------------------------------------- statements (Q.04)

    /** A customer's statement for a period on A4 (the last three months by default). */
    @GetMapping("/customers/{id}/statement")
    @PreAuthorize("hasAuthority('PAGE_CUSTOMERS') and hasAuthority('PERM_VIEW_CUSTOMER_ACCOUNT')")
    public ResponseEntity<byte[]> statement(@PathVariable UUID id,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Customer customer = customerService.findById(id);
        LocalDate end = to == null ? creditControl.today() : to;
        LocalDate start = from == null ? end.minusMonths(3).withDayOfMonth(1) : from;
        if (start.isAfter(end)) {
            start = end.withDayOfMonth(1);
        }
        Map<String, Object> model = reportFiles.letterhead();
        model.put("customer", customer);
        model.put("statement", creditControl.statement(customer, start, end));
        byte[] body = pdfService.render("accounting/statement-pdf", model);
        activityLogService.record(MODULE, "EXPORT_STATEMENT", "Downloaded the statement of " + customer.getName() + " from " + start.format(DAY)
                + " to " + end.format(DAY) + " to PDF", ActivityStatus.SUCCESS);
        return ReportFiles.file("statement-" + customer.getCode() + "-" + end + ".pdf", MediaType.APPLICATION_PDF, body);
    }

    private YearMonth month(String month) {
        YearMonth last = YearMonth.from(creditControl.today());
        if (month == null || month.isBlank()) {
            return last;
        }
        try {
            YearMonth m = YearMonth.parse(month);
            return m.isAfter(last) ? last : m;
        } catch (DateTimeParseException e) {
            return last;
        }
    }
}
