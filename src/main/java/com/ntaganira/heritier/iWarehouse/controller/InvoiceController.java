package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : InvoiceController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Issued sales invoices (POS-01, POS-04, TAX-01, TAX-04): the list, an invoice with its lines, VAT
 *               per tax letter, payments, journal and History, and its receipt to print (80 mm).
 *               PAGE_INVOICES + PERM_VIEW_INVOICE.
 * </pre>
 */
@Controller
@RequestMapping("/invoices")
public class InvoiceController {

    private final SalesService salesService;
    private final JournalService journalService;
    private final DataChangeService dataChangeService;
    private final SettingService settingService;
    private final ActivityLogService activityLogService;

    public InvoiceController(SalesService salesService, JournalService journalService, DataChangeService dataChangeService,
                             SettingService settingService, ActivityLogService activityLogService) {
        this.salesService = salesService;
        this.journalService = journalService;
        this.dataChangeService = dataChangeService;
        this.settingService = settingService;
        this.activityLogService = activityLogService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_INVOICES') and hasAuthority('PERM_VIEW_INVOICE')")
    public String list(@RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("invoices", salesService.findPage(search, Paging.page(page), Paging.SIZE));
        model.addAttribute("search", search);
        model.addAttribute("paginationQuery", QueryString.of("search", search));
        return "invoices/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_INVOICES') and hasAuthority('PERM_VIEW_INVOICE')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "lines") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        String open = List.of("lines", "history").contains(tab) ? tab : "lines";
        SalesInvoice invoice = issued(id);
        model.addAttribute("invoice", invoice);
        model.addAttribute("totals", salesService.totals(invoice));
        model.addAttribute("payments", salesService.payments(id));
        model.addAttribute("journals", journalService.forSource(id, JournalSource.SALES_INVOICE));
        model.addAttribute("history", dataChangeService.historyWithChildren("SalesInvoice", id.toString(), "SalesInvoiceLine",
                "invoice", Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "invoices/view";
    }

    /** The receipt to print on the 80 mm receipt printer. */
    @GetMapping("/{id}/receipt")
    @PreAuthorize("hasAuthority('PAGE_INVOICES') and hasAuthority('PERM_VIEW_INVOICE')")
    public String receipt(@PathVariable UUID id, Model model) {
        SalesInvoice invoice = issued(id);
        model.addAttribute("invoice", invoice);
        model.addAttribute("totals", salesService.totals(invoice));
        model.addAttribute("payments", salesService.payments(id));
        model.addAttribute("companyName", settingService.get(SettingKey.COMPANY_NAME));
        model.addAttribute("companyTin", settingService.get(SettingKey.COMPANY_TIN));
        model.addAttribute("companyAddress", settingService.get(SettingKey.COMPANY_ADDRESS));
        model.addAttribute("companyPhone", settingService.get(SettingKey.COMPANY_PHONE));
        activityLogService.record(PosController.MODULE, "PRINT_RECEIPT", "Opened the receipt of " + invoice.getNumber() + " for printing",
                ActivityStatus.SUCCESS);
        return "invoices/receipt";
    }

    /** Only issued invoices have a page: a sale being rung up lives on the POS. */
    private SalesInvoice issued(UUID id) {
        SalesInvoice invoice = salesService.findDetailed(id);
        if (invoice.getStatus() != SalesInvoiceStatus.POSTED) {
            throw new NotFoundException("SalesInvoice", id);
        }
        return invoice;
    }
}
