package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : DeliveryNoteController.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Delivery notes and the release of goods (F-04, DN-005, IMP 8 and 9; decision D3): the list with delivery
 *               accuracy, preparing the release of a paid invoice, a note with what it releases, its EBM receipt, its
 *               documents (the signed copy) and History; release it (another person; an override reason when the EBM
 *               receipt waits), cancel it with a reason, record the signed copy, print it in three copies.
 *               PAGE_DELIVERY_NOTES + PERM_VIEW_DELIVERY_NOTE; preparing and the copy PERM_PREPARE_DELIVERY; releasing
 *               PERM_RELEASE_GOODS.
 * </pre>
 */
@Controller
@RequestMapping("/delivery-notes")
public class DeliveryNoteController {

    static final String MODULE = "Delivery Notes";
    private static final String VIEW = "hasAuthority('PAGE_DELIVERY_NOTES') and hasAuthority('PERM_VIEW_DELIVERY_NOTE')";
    private static final String PREPARE = "hasAuthority('PAGE_DELIVERY_NOTES') and hasAuthority('PERM_PREPARE_DELIVERY')";

    private final DeliveryNoteService noteService;
    private final SalesService salesService;
    private final StockService stockService;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final SettingService settingService;
    private final CompanyLogoService companyLogoService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public DeliveryNoteController(DeliveryNoteService noteService, SalesService salesService, StockService stockService,
                                  AttachmentService attachmentService, DataChangeService dataChangeService, SettingService settingService,
                                  CompanyLogoService companyLogoService, ActivityLogService activityLogService, Messages messages) {
        this.noteService = noteService;
        this.salesService = salesService;
        this.stockService = stockService;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.settingService = settingService;
        this.companyLogoService = companyLogoService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String list(@RequestParam(required = false) String status, @RequestParam(required = false) String search,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        LocalDate today = noteService.today();
        model.addAttribute("notes", noteService.findPage(status, search, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", List.of("DRAFT", "RELEASED", "copy", "overdue", "CANCELLED"));
        model.addAttribute("status", status);
        model.addAttribute("search", search);
        model.addAttribute("accuracy", noteService.accuracy(today.withDayOfMonth(1), today));
        model.addAttribute("waiting", noteService.waitingRelease());
        model.addAttribute("copyDays", settingService.getInt(SettingKey.DELIVERY_SIGNED_COPY_DAYS));
        model.addAttribute("paginationQuery", QueryString.of("status", status, "search", search));
        return "delivery-notes/list";
    }

    @GetMapping("/new")
    @PreAuthorize(PREPARE)
    public String form(@RequestParam UUID invoice, Model model, RedirectAttributes redirect) {
        SalesInvoice inv = salesService.findDetailed(invoice);
        DeliveryNoteService.Releasable ready = noteService.releasable(inv);
        if (ready.isEmpty()) {
            redirect.addFlashAttribute("flashError", messages.get("delivery.nothing", inv.getNumber()));
            return "redirect:/invoices/" + invoice;
        }
        return form(model, inv, ready);
    }

    @PostMapping("/new")
    @PreAuthorize(PREPARE)
    public String prepare(@RequestParam UUID invoice, @RequestParam(required = false) List<UUID> unitIds,
                          @RequestParam(defaultValue = "false") boolean collected, @RequestParam(required = false) String vehiclePlate,
                          @RequestParam(required = false) String driverName, @RequestParam(required = false) String destination,
                          @RequestParam(required = false) String shortReason, Model model, RedirectAttributes redirect) {
        try {
            DeliveryNote n = noteService.prepare(invoice, new DeliveryNoteService.Form(unitIds, collected, vehiclePlate, driverName,
                    destination, shortReason));
            activityLogService.record(MODULE, "CREATE_DELIVERY_NOTE", "Prepared delivery note " + n.getNumber() + " for invoice "
                    + n.getInvoice().getNumber() + ": " + (unitIds == null ? 0 : unitIds.size()) + " unit(s), "
                    + (collected ? "collected by the customer" : n.getVehiclePlate() + " driven by " + n.getDriverName())
                    + (n.getShortReason() == null ? "" : ", short: " + n.getShortReason()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("delivery.prepared", n.getNumber()));
            return "redirect:/delivery-notes/" + n.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_DELIVERY_NOTE", "Failed to prepare a delivery note for invoice " + invoice + ": " + error,
                    ActivityStatus.FAILED);
            SalesInvoice inv = salesService.findDetailed(invoice);
            model.addAttribute("flashError", error);
            model.addAttribute("errorField", e.getField());
            model.addAttribute("chosen", unitIds == null ? Set.of() : new HashSet<>(unitIds));
            model.addAttribute("collected", collected);
            model.addAttribute("vehiclePlate", vehiclePlate);
            model.addAttribute("driverName", driverName);
            model.addAttribute("destination", destination);
            model.addAttribute("shortReason", shortReason);
            return form(model, inv, noteService.releasable(inv));
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "lines") String tab, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        DeliveryNote n = noteService.findDetailed(id);
        String open = List.of("documents", "history").contains(tab) ? tab : "lines";
        addNote(model, n);
        model.addAttribute("receipt", noteService.receiptOf(n.getInvoice()).orElse(null));
        model.addAttribute("ebmBeforeRelease", noteService.ebmBeforeRelease());
        model.addAttribute("preparer", DeliveryNoteService.isPreparer(n));
        model.addAttribute("today", noteService.today());
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.DELIVERY_NOTE, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("DeliveryNote", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "delivery-notes/view";
    }

    /** The delivery note to print (A4, three copies: warehouse, finance, customer). */
    @GetMapping("/{id}/print")
    @PreAuthorize(VIEW)
    public String print(@PathVariable UUID id, Model model) {
        DeliveryNote n = noteService.findDetailed(id);
        addNote(model, n);
        model.addAttribute("receipt", noteService.receiptOf(n.getInvoice()).orElse(null));
        model.addAttribute("copies", List.of("warehouse", "finance", "customer"));
        model.addAttribute("companyName", settingService.get(SettingKey.COMPANY_NAME));
        model.addAttribute("companyLogoUrl", companyLogoService.url());
        model.addAttribute("companyTin", settingService.get(SettingKey.COMPANY_TIN));
        model.addAttribute("companyAddress", settingService.get(SettingKey.COMPANY_ADDRESS));
        model.addAttribute("companyPhone", settingService.get(SettingKey.COMPANY_PHONE));
        activityLogService.record(MODULE, "PRINT_DELIVERY_NOTE", "Opened delivery note " + n.getNumber() + " for printing", ActivityStatus.SUCCESS);
        return "delivery-notes/print";
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('PAGE_DELIVERY_NOTES') and hasAuthority('PERM_RELEASE_GOODS')")
    public String release(@PathVariable UUID id, @RequestParam(required = false) String overrideReason, RedirectAttributes redirect) {
        try {
            DeliveryNote n = noteService.release(id, overrideReason);
            activityLogService.record(MODULE, "RELEASE_GOODS", "Released delivery note " + n.getNumber() + " of invoice "
                    + n.getInvoice().getNumber() + (n.getEbmOverrideReason() == null ? ", EBM receipt " + n.getEbmReceipt()
                    : ", before the EBM signature: " + n.getEbmOverrideReason()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("delivery.released", n.getNumber()));
        } catch (BusinessException e) {
            failed(id, "RELEASE_GOODS", "release", e, redirect);
        }
        return "redirect:/delivery-notes/" + id;
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_DELIVERY_NOTES') and (hasAuthority('PERM_PREPARE_DELIVERY') or hasAuthority('PERM_RELEASE_GOODS'))")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > 255) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/delivery-notes/" + id;
        }
        try {
            DeliveryNote n = AuditContext.withReason(reason.trim(), () -> noteService.cancel(id, reason));
            activityLogService.record(MODULE, "CANCEL_DELIVERY_NOTE", "Cancelled delivery note " + n.getNumber() + ": " + reason.trim(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("delivery.cancelled", n.getNumber()));
        } catch (BusinessException e) {
            failed(id, "CANCEL_DELIVERY_NOTE", "cancel", e, redirect);
        }
        return "redirect:/delivery-notes/" + id;
    }

    /** The copy signed by the customer is back: who signed, when, their remarks. */
    @PostMapping("/{id}/copy")
    @PreAuthorize(PREPARE)
    public String copy(@PathVariable UUID id, @RequestParam(required = false) String receivedByName,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedOn,
                       @RequestParam(required = false) String remarks, @RequestParam(defaultValue = "false") boolean issue,
                       RedirectAttributes redirect) {
        try {
            DeliveryNote n = noteService.recordCopy(id, new DeliveryNoteService.Copy(receivedByName, receivedOn, remarks, issue));
            activityLogService.record(MODULE, "UPDATE_DELIVERY_NOTE", "Recorded the signed copy of " + n.getNumber() + ": signed by "
                    + n.getReceivedByName() + " on " + n.getReceivedOn() + (n.isIssue() ? ", shortage or damage: " + n.getRemarks() : ""),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("delivery.copyRecorded", n.getNumber()));
        } catch (BusinessException e) {
            failed(id, "UPDATE_DELIVERY_NOTE", "record the signed copy of", e, redirect);
        }
        return "redirect:/delivery-notes/" + id;
    }

    // ---------------------------------------------------------------- helpers

    private String form(Model model, SalesInvoice invoice, DeliveryNoteService.Releasable ready) {
        model.addAttribute("invoice", invoice);
        model.addAttribute("ready", ready);
        model.addAttribute("locations", stockService.locationsById());
        if (!model.containsAttribute("chosen")) {
            Set<UUID> all = new HashSet<>();
            ready.sheets().forEach(u -> all.add(u.getId()));
            ready.pieces().forEach(u -> all.add(u.getId()));
            model.addAttribute("chosen", all);
            model.addAttribute("collected", false);
        }
        return "delivery-notes/form";
    }

    /** The note, its lines and the invoice lines they come from, its site. */
    private void addNote(Model model, DeliveryNote n) {
        SalesInvoice invoice = salesService.findDetailed(n.getInvoice().getId());
        Map<UUID, SalesInvoiceLine> invoiceLines = invoice.getLines().stream().filter(l -> l.getId() != null)
                .collect(Collectors.toMap(SalesInvoiceLine::getId, Function.identity()));
        Map<UUID, Location> places = stockService.locationsById();
        List<DeliveryNoteLine> lines = noteService.lines(n.getId());
        Map<UUID, Long> releasedOf = lines.stream().collect(Collectors.groupingBy(DeliveryNoteLine::getInvoiceLineId, Collectors.counting()));
        List<Released> summary = invoice.getLines().stream().filter(l -> releasedOf.containsKey(l.getId()))
                .map(l -> new Released(l, releasedOf.get(l.getId()))).toList();
        model.addAttribute("note", n);
        model.addAttribute("invoice", invoice);
        model.addAttribute("lines", lines);
        model.addAttribute("summary", summary);
        model.addAttribute("invoiceLines", invoiceLines);
        model.addAttribute("site", n.getSiteId() == null ? null : places.get(n.getSiteId()));
        model.addAttribute("orderNumber", invoice.getQuotationId() == null ? null : salesService.orderNumber(invoice.getQuotationId()));
    }

    /** An invoice line and how many of its units this note releases (F-04: quantity ordered and released). */
    public record Released(SalesInvoiceLine line, long released) {
    }

    private void failed(UUID id, String action, String verb, BusinessException e, RedirectAttributes redirect) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        String number;
        try {
            number = noteService.findDetailed(id).getNumber();
        } catch (RuntimeException ex) {
            number = id.toString();
        }
        activityLogService.record(MODULE, action, "Failed to " + verb + " delivery note " + number + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }
}
