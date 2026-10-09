package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.CreditNoteDto;
import com.ntaganira.heritier.iWarehouse.entity.CreditNote;
import com.ntaganira.heritier.iWarehouse.entity.CreditNoteLine;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoiceLine;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : CreditNoteController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Returns and credit notes (POS-09): the return form of an invoice (what comes back, where it goes, the
 *               reason and the refund), the list, a credit note with its lines, units, refund, journal and History, and
 *               its 80 mm slip to print.
 * </pre>
 */
@Controller
@RequestMapping("/credit-notes")
public class CreditNoteController {

    private final CreditNoteService creditNoteService;
    private final SalesService salesService;
    private final TillService tillService;
    private final StockService stockService;
    private final JournalService journalService;
    private final DataChangeService dataChangeService;
    private final SettingService settingService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public CreditNoteController(CreditNoteService creditNoteService, SalesService salesService, TillService tillService,
                                StockService stockService, JournalService journalService, DataChangeService dataChangeService,
                                SettingService settingService, ActivityLogService activityLogService, Messages messages,
                                NumberFormats num) {
        this.creditNoteService = creditNoteService;
        this.salesService = salesService;
        this.tillService = tillService;
        this.stockService = stockService;
        this.journalService = journalService;
        this.dataChangeService = dataChangeService;
        this.settingService = settingService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_CREDIT_NOTES') and hasAuthority('PERM_VIEW_CREDIT_NOTE')")
    public String list(@RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("creditNotes", creditNoteService.findPage(search, Paging.page(page), Paging.SIZE));
        model.addAttribute("search", search);
        model.addAttribute("paginationQuery", QueryString.of("search", search));
        return "credit-notes/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_NOTES') and hasAuthority('PERM_VIEW_CREDIT_NOTE')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "lines") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        String open = List.of("lines", "history").contains(tab) ? tab : "lines";
        CreditNote note = creditNoteService.findDetailed(id);
        details(model, note);
        model.addAttribute("units", creditNoteService.units(id));
        model.addAttribute("till", note.getTillSessionId() == null ? null : tillService.findById(note.getTillSessionId()));
        model.addAttribute("journals", journalService.forSource(id, JournalSource.CREDIT_NOTE));
        model.addAttribute("history", dataChangeService.history("CreditNote", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "credit-notes/view";
    }

    /** The credit note slip for the 80 mm receipt printer. */
    @GetMapping("/{id}/receipt")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_NOTES') and hasAuthority('PERM_VIEW_CREDIT_NOTE')")
    public String receipt(@PathVariable UUID id, Model model) {
        CreditNote note = creditNoteService.findDetailed(id);
        details(model, note);
        model.addAttribute("companyName", settingService.get(SettingKey.COMPANY_NAME));
        model.addAttribute("companyTin", settingService.get(SettingKey.COMPANY_TIN));
        model.addAttribute("companyAddress", settingService.get(SettingKey.COMPANY_ADDRESS));
        model.addAttribute("companyPhone", settingService.get(SettingKey.COMPANY_PHONE));
        activityLogService.record(PosController.MODULE, "PRINT_CREDIT_NOTE", "Opened credit note " + note.getNumber() + " for printing",
                ActivityStatus.SUCCESS);
        return "credit-notes/receipt";
    }

    // ---------------------------------------------------------------- the return (POS-09)

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_INVOICES') and hasAuthority('PERM_RETURN_SALE')")
    public String newForm(@RequestParam UUID invoice, Model model) {
        SalesInvoice sale = issued(invoice);
        return form(model, creditNoteService.newForm(sale), sale);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PAGE_INVOICES') and hasAuthority('PERM_RETURN_SALE')")
    public String issue(@ModelAttribute("creditNoteDto") CreditNoteDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        SalesInvoice sale = issued(dto.getInvoiceId());
        try {
            CreditNote note = creditNoteService.issue(dto);
            activityLogService.record(PosController.MODULE, "CREATE_CREDIT_NOTE", "Issued credit note " + note.getNumber() + " on "
                    + sale.getNumber() + ": " + num.money(note.getTotalAmount()) + " RWF"
                    + (note.isRefunded() ? ", refunded " + num.money(note.getRefundAmount()) + " RWF by " + note.getRefundMethod() : "")
                    + (note.getBalanceReduced().signum() > 0 ? ", balance due reduced by " + num.money(note.getBalanceReduced()) + " RWF" : "")
                    + ": " + note.getReason(), ActivityStatus.SUCCESS);
            String text = messages.get("creditNote.issued", note.getNumber(), num.money(note.getTotalAmount()));
            if (note.getRefundMethod() == PaymentMethod.CASH) {
                text = text + ". " + messages.get("creditNote.giveCash", num.money(note.getRefundAmount()));
            }
            redirect.addFlashAttribute("flashSuccess", text);
            return "redirect:/credit-notes/" + note.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(PosController.MODULE, "CREATE_CREDIT_NOTE", "Failed to issue a credit note on " + sale.getNumber()
                    + ": " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
            } else {
                model.addAttribute("flashError", error);
            }
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto, issued(dto.getInvoiceId()));
        }
    }

    // ---------------------------------------------------------------- helpers

    private String form(Model model, CreditNoteDto dto, SalesInvoice sale) {
        model.addAttribute("creditNoteDto", dto);
        model.addAttribute("invoice", sale);
        model.addAttribute("returnables", creditNoteService.returnables(sale).stream()
                .collect(Collectors.toMap(r -> r.unit().getId(), Function.identity())));
        model.addAttribute("places", stockService.storagePlaces(stockService.locationsById()));
        List<PaymentMethod> methods = new ArrayList<>(List.of(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY, PaymentMethod.CARD,
                PaymentMethod.BANK_TRANSFER));
        if (sale.getCustomer().getType().isCreditAllowed()) {
            methods.add(PaymentMethod.CREDIT);
        }
        model.addAttribute("refundMethods", methods);
        model.addAttribute("myTill", tillService.current().map(tillService::summary).orElse(null));
        if (!model.containsAttribute("formErrors")) {
            model.addAttribute("formErrors", new BeanPropertyBindingResult(dto, "creditNoteDto").getFieldErrors());
        }
        return "credit-notes/form";
    }

    /** What the view and the slip show: the credit note, its invoice's lines (what each credit line is), its lines and VAT. */
    private void details(Model model, CreditNote note) {
        SalesInvoice invoice = salesService.findDetailed(note.getInvoice().getId());
        List<CreditNoteLine> lines = creditNoteService.lines(note.getId());
        model.addAttribute("note", note);
        model.addAttribute("invoice", invoice);
        model.addAttribute("invoiceLines", invoice.getLines().stream().collect(Collectors.toMap(SalesInvoiceLine::getId, Function.identity())));
        model.addAttribute("lines", lines);
        model.addAttribute("totals", Vat.totals(lines.stream().map(l -> new Vat.Line(l.getTaxCode(), l.getVatRate(), l.getAmount())).toList()));
    }

    /** Returns are taken against issued invoices only. */
    private SalesInvoice issued(UUID id) {
        if (id == null) {
            throw new NotFoundException("SalesInvoice", null);
        }
        SalesInvoice invoice = salesService.findDetailed(id);
        if (invoice.getStatus() != SalesInvoiceStatus.POSTED) {
            throw new NotFoundException("SalesInvoice", id);
        }
        return invoice;
    }
}
