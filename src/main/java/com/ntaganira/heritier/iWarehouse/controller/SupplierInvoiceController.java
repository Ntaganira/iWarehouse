package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.service.AttachmentService;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import com.ntaganira.heritier.iWarehouse.entity.SupplierInvoice;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : SupplierInvoiceController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Supplier invoices (ACC-09): recording one for a supplier's goods receipts not invoiced yet, the list, and
 *               an invoice with the receipts it billed, its journal and History.
 * </pre>
 */
@Controller
@RequestMapping("/supplier-invoices")
public class SupplierInvoiceController {

    static final String MODULE = "Procurement";

    private final AttachmentService attachmentService;
    private final SupplierAccountService accountService;
    private final SupplierService supplierService;
    private final JournalService journalService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public SupplierInvoiceController(SupplierAccountService accountService, SupplierService supplierService, JournalService journalService,
                                     DataChangeService dataChangeService, ActivityLogService activityLogService, Messages messages,
                                     NumberFormats num,
            AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
        this.accountService = accountService;
        this.supplierService = supplierService;
        this.journalService = journalService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_SUPPLIER_INVOICES') and hasAuthority('PERM_VIEW_SUPPLIER_ACCOUNT')")
    public String list(@RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("invoices", accountService.findInvoices(search, Paging.page(page), Paging.SIZE));
        model.addAttribute("search", search);
        model.addAttribute("paginationQuery", QueryString.of("search", search));
        return "supplier-invoices/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIER_INVOICES') and hasAuthority('PERM_VIEW_SUPPLIER_ACCOUNT')")
    public String view(@PathVariable UUID id, @RequestParam(required = false) String tab, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        String open = "documents".equals(tab) ? tab : "history";
        model.addAttribute("invoice", accountService.findInvoice(id));
        model.addAttribute("lines", accountService.invoiceLines(id));
        model.addAttribute("journals", journalService.forSource(id, JournalSource.SUPPLIER_INVOICE));
        model.addAttribute("history", dataChangeService.historyWithChildren("SupplierInvoice", id.toString(), List.of("Attachment"), "ownerId",
                Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.SUPPLIER_INVOICE, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        return "supplier-invoices/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIERS') and hasAuthority('PERM_RECORD_SUPPLIER_INVOICE')")
    public String newForm(@RequestParam UUID supplier, Model model) {
        return form(model, supplierService.findById(supplier), null, accountService.today(), null, List.of(), null);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PAGE_SUPPLIERS') and hasAuthority('PERM_RECORD_SUPPLIER_INVOICE')")
    public String record(@RequestParam UUID supplierId, @RequestParam(required = false) String supplierRef,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate invoiceDate,
                         @RequestParam(required = false) BigDecimal amount, @RequestParam(required = false) List<UUID> receiptIds,
                         @RequestParam(required = false) String notes, Model model, RedirectAttributes redirect) {
        Supplier supplier = supplierService.findById(supplierId);
        List<UUID> ticked = receiptIds == null ? List.of() : receiptIds;
        try {
            SupplierInvoice invoice = accountService.recordInvoice(supplierId,
                    new SupplierAccountService.InvoiceForm(supplierRef, invoiceDate, amount, ticked, notes));
            activityLogService.record(MODULE, "CREATE_SUPPLIER_INVOICE", "Recorded supplier invoice " + invoice.getNumber() + " ("
                    + invoice.getSupplierRef() + ") from " + supplier.getName() + ": " + num.amount(invoice.getAmount(), 2) + " "
                    + invoice.getCurrencyCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("supplierInvoice.recorded", invoice.getNumber(),
                    num.amount(invoice.getAmount(), 2), invoice.getCurrencyCode()));
            return "redirect:/supplier-invoices/" + invoice.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_SUPPLIER_INVOICE", "Failed to record an invoice from " + supplier.getName() + ": " + error,
                    ActivityStatus.FAILED);
            return form(model, supplier, supplierRef, invoiceDate, amount, ticked, notes, e.getField(), error);
        }
    }

    private String form(Model model, Supplier supplier, String ref, LocalDate date, BigDecimal amount, List<UUID> ticked, String notes) {
        return form(model, supplier, ref, date, amount, ticked, notes, null, null);
    }

    private String form(Model model, Supplier supplier, String ref, LocalDate date, BigDecimal amount, List<UUID> ticked, String notes,
                        String field, String error) {
        model.addAttribute("supplier", supplier);
        model.addAttribute("uninvoiced", accountService.uninvoiced(supplier));
        model.addAttribute("supplierRef", ref);
        model.addAttribute("invoiceDate", date);
        model.addAttribute("amount", amount);
        model.addAttribute("ticked", ticked);
        model.addAttribute("notes", notes);
        model.addAttribute("errorField", field);
        model.addAttribute("errorText", error);
        return "supplier-invoices/form";
    }
}
