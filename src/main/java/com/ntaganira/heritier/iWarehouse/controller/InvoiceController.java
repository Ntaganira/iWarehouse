package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoiceLine;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : InvoiceController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Issued sales invoices (POS-01, POS-04, TAX-01, TAX-04): the list, an invoice with its lines, VAT
 *               per tax letter, payments, journal and History, and its receipt to print (80 mm); handing over
 *               the pieces of its custom sizes (POS-02, PERM_DELIVER_SALE). PAGE_INVOICES + PERM_VIEW_INVOICE.
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
    private final Messages messages;

    public InvoiceController(SalesService salesService, JournalService journalService, DataChangeService dataChangeService,
                             SettingService settingService, ActivityLogService activityLogService,
                             Messages messages) {
        this.salesService = salesService;
        this.journalService = journalService;
        this.dataChangeService = dataChangeService;
        this.settingService = settingService;
        this.activityLogService = activityLogService;
        this.messages = messages;
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
        model.addAttribute("journals", journalService.forSource(id, JournalSource.SALES_INVOICE, JournalSource.SALES_DELIVERY));
        // Sizes to cut (POS-02): their cutting jobs, what is handed over, the pieces ready to hand over
        boolean custom = invoice.getLines().stream().anyMatch(SalesInvoiceLine::isCustomPiece);
        model.addAttribute("custom", custom);
        Map<UUID, SalesService.Progress> progress = custom ? salesService.progress(invoice) : Map.of();
        model.addAttribute("progress", progress);
        model.addAttribute("toHandOver", progress.values().stream().mapToInt(SalesService.Progress::getRemaining).sum());
        model.addAttribute("jobs", custom ? salesService.jobsOf(invoice) : List.of());
        model.addAttribute("ready", custom ? salesService.readyPieces(invoice) : List.of());
        model.addAttribute("deliveries", custom ? salesService.deliveries(id) : List.of());
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

    /** Hands over pieces of the invoice's sizes (SRS 5.3 step 5): ticked or scanned. */
    @PostMapping("/{id}/deliver")
    @PreAuthorize("hasAuthority('PAGE_INVOICES') and hasAuthority('PERM_DELIVER_SALE')")
    public String deliver(@PathVariable UUID id, @RequestParam(required = false) List<UUID> unitIds,
                          @RequestParam(required = false) String codes, RedirectAttributes redirect) {
        try {
            SalesService.Delivered d = salesService.deliver(id, unitIds, codes);
            String units = d.units().stream().map(StockUnit::getCode).collect(Collectors.joining(", "));
            activityLogService.record(PosController.MODULE, "DELIVER_SALE", "Handed over " + units + " of " + d.invoice().getNumber()
                    + (d.journal() == null ? "" : ", journal " + d.journal().getNumber()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("sale.delivered", d.units().size(), d.invoice().getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(PosController.MODULE, "DELIVER_SALE", "Failed to hand over pieces of invoice " + id + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/invoices/" + id;
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
