package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.SiteTransferDto;
import com.ntaganira.heritier.iWarehouse.entity.SiteTransfer;
import com.ntaganira.heritier.iWarehouse.entity.SiteTransferUnit;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.SiteTransferStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : SiteTransferController.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Transfers between sites (IMP 11, TRF-007, TCN-008, PF-04): the list, the request form, a transfer with what
 *               was asked, the units sent and received, its tickets, documents and History; approve, reject, cancel,
 *               dispatch (labels, vehicle, driver, expected arrival), receive label by label, finish short with a note, and
 *               the delivery note for the driver. PAGE_SITE_TRANSFERS + PERM_VIEW_SITE_TRANSFER; ask
 *               PERM_REQUEST_SITE_TRANSFER; approve, reject PERM_APPROVE_SITE_TRANSFER; dispatch PERM_DISPATCH_SITE_TRANSFER;
 *               receive and finish PERM_RECEIVE_SITE_TRANSFER.
 * </pre>
 */
@Controller
@RequestMapping("/site-transfers")
public class SiteTransferController {

    static final String MODULE = "Site Transfers";
    private static final String VIEW = "hasAuthority('PAGE_SITE_TRANSFERS') and hasAuthority('PERM_VIEW_SITE_TRANSFER')";

    private final SiteTransferService transferService;
    private final TicketService ticketService;
    private final ProductRepository productRepo;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final SettingService settingService;
    private final CompanyLogoService companyLogoService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public SiteTransferController(SiteTransferService transferService, TicketService ticketService, ProductRepository productRepo,
                                  AttachmentService attachmentService, DataChangeService dataChangeService, SettingService settingService,
                                  CompanyLogoService companyLogoService, ActivityLogService activityLogService, Messages messages) {
        this.transferService = transferService;
        this.ticketService = ticketService;
        this.productRepo = productRepo;
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
        model.addAttribute("transfers", transferService.findPage(status, search, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", SiteTransferStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("search", search);
        model.addAttribute("paginationQuery", QueryString.of("status", status, "search", search));
        return "site-transfers/list";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_SITE_TRANSFERS') and hasAuthority('PERM_REQUEST_SITE_TRANSFER')")
    public String form(Model model) {
        SiteTransferDto dto = new SiteTransferDto();
        dto.setExpectedDispatch(transferService.today().plusDays(1));
        dto.getLines().add(new SiteTransferDto.Line());
        return form(model, dto);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_SITE_TRANSFERS') and hasAuthority('PERM_REQUEST_SITE_TRANSFER')")
    public String request(@ModelAttribute("transferDto") SiteTransferDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        dto.getLines().removeIf(SiteTransferDto.Line::isBlank);
        try {
            SiteTransfer t = transferService.request(new SiteTransferService.Form(dto.getFromSiteId(), dto.getToSiteId(), dto.getReason(),
                    dto.getExpectedDispatch(), dto.getLines().stream()
                    .map(l -> new SiteTransferService.Line(l.getProductId(), l.getWidthMm(), l.getHeightMm(), l.getQuantity())).toList()));
            activityLogService.record(MODULE, "CREATE_SITE_TRANSFER", "Asked for transfer " + t.getNumber() + " from " + t.getFromSite().getName()
                    + " to " + t.getToSite().getName() + " (" + dto.getLines().size() + " line(s)): " + t.getReason(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(t.getStatus() == SiteTransferStatus.REQUESTED
                    ? "siteTransfer.created" : "siteTransfer.createdApproved", t.getNumber()));
            return "redirect:/site-transfers/" + t.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_SITE_TRANSFER", "Failed to ask for a transfer: " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
            } else {
                model.addAttribute("flashError", error);
            }
            if (dto.getLines().isEmpty()) {
                dto.getLines().add(new SiteTransferDto.Line());
            }
            return form(model, dto);
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "lines") String tab, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        SiteTransfer t = transferService.findDetailed(id);
        String open = List.of("tickets", "documents", "history").contains(tab) ? tab : "lines";
        List<SiteTransferUnit> units = transferService.units(id);
        model.addAttribute("transfer", t);
        model.addAttribute("lines", transferService.lines(id));
        model.addAttribute("units", units);
        model.addAttribute("unitsById", transferService.unitsById(id));
        model.addAttribute("locations", transferService.locations());
        model.addAttribute("sentByLine", transferService.sentByLine(id));
        model.addAttribute("received", units.stream().filter(SiteTransferUnit::isReceived).count());
        model.addAttribute("mine", SiteTransferService.isRequester(t));
        model.addAttribute("places", t.getStatus() == SiteTransferStatus.IN_TRANSIT || t.getStatus() == SiteTransferStatus.RECEIVED_SHORT
                ? transferService.placesOf(t.getToSite().getId()) : List.of());
        model.addAttribute("tickets", ticketService.ofSource(id));
        model.addAttribute("now", LocalDateTime.now().withSecond(0).withNano(0));
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.SITE_TRANSFER, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.historyWithChildren("SiteTransfer", id.toString(), "SiteTransferUnit", "transferId",
                Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "site-transfers/view";
    }

    /** The transfer delivery note the driver carries (TCN-008): what left, from where, the vehicle and the arrival. */
    @GetMapping("/{id}/note")
    @PreAuthorize(VIEW)
    public String note(@PathVariable UUID id, Model model) {
        SiteTransfer t = transferService.findDetailed(id);
        Map<UUID, StockUnit> byId = transferService.unitsById(id);
        model.addAttribute("transfer", t);
        model.addAttribute("units", transferService.units(id));
        model.addAttribute("unitsById", byId);
        model.addAttribute("totalArea", byId.values().stream().map(StockUnit::getAreaM2).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("locations", transferService.locations());
        model.addAttribute("companyName", settingService.get(SettingKey.COMPANY_NAME));
        model.addAttribute("companyLogoUrl", companyLogoService.url());
        model.addAttribute("companyAddress", settingService.get(SettingKey.COMPANY_ADDRESS));
        model.addAttribute("companyPhone", settingService.get(SettingKey.COMPANY_PHONE));
        return "site-transfers/note";
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PAGE_SITE_TRANSFERS') and hasAuthority('PERM_APPROVE_SITE_TRANSFER')")
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            SiteTransfer t = transferService.approve(id, note);
            log("APPROVE_SITE_TRANSFER", "Approved transfer " + t.getNumber() + " of " + t.getRequestedBy(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("siteTransfer.approved", t.getNumber()));
        } catch (BusinessException e) {
            failed(id, "APPROVE_SITE_TRANSFER", "approve", e, redirect);
        }
        return "redirect:/site-transfers/" + id;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAGE_SITE_TRANSFERS') and hasAuthority('PERM_APPROVE_SITE_TRANSFER')")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return withReason(id, reason, redirect, true);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_SITE_TRANSFERS') and (hasAuthority('PERM_REQUEST_SITE_TRANSFER') or hasAuthority('PERM_APPROVE_SITE_TRANSFER'))")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return withReason(id, reason, redirect, false);
    }

    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAuthority('PAGE_SITE_TRANSFERS') and hasAuthority('PERM_DISPATCH_SITE_TRANSFER')")
    public String dispatch(@PathVariable UUID id, @RequestParam(required = false) String codes, @RequestParam(required = false) String vehiclePlate,
                           @RequestParam(required = false) String driverName,
                           @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime expectedArrival,
                           RedirectAttributes redirect) {
        try {
            List<String> labels = codes == null ? List.of() : Arrays.stream(codes.split("[\\s,;]+")).filter(StringUtils::hasText).toList();
            SiteTransfer t = transferService.dispatch(id, new SiteTransferService.Dispatch(labels, vehiclePlate, driverName, expectedArrival));
            log("DISPATCH_SITE_TRANSFER", "Dispatched transfer " + t.getNumber() + " to " + t.getToSite().getName() + ": " + labels.size()
                    + " unit(s) on " + t.getVehiclePlate() + " driven by " + t.getDriverName(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("siteTransfer.dispatchedMsg", t.getNumber(), labels.size()));
        } catch (BusinessException e) {
            failed(id, "DISPATCH_SITE_TRANSFER", "dispatch", e, redirect);
            redirect.addFlashAttribute("dispatchCodes", codes);
            redirect.addFlashAttribute("dispatchVehicle", vehiclePlate);
            redirect.addFlashAttribute("dispatchDriver", driverName);
        }
        return "redirect:/site-transfers/" + id;
    }

    /** A label scanned at the receiving site; the page comes back for the next one. */
    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('PAGE_SITE_TRANSFERS') and hasAuthority('PERM_RECEIVE_SITE_TRANSFER')")
    public String receive(@PathVariable UUID id, @RequestParam(required = false) String code, @RequestParam(required = false) UUID locationId,
                          RedirectAttributes redirect) {
        try {
            SiteTransferUnit unit = transferService.receive(id, code, locationId);
            SiteTransfer t = transferService.findDetailed(id);
            log("RECEIVE_SITE_TRANSFER", "Received " + unit.getUnitCode() + " on transfer " + t.getNumber(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", t.getStatus() == SiteTransferStatus.RECEIVED
                    ? messages.get("siteTransfer.receivedAll", t.getNumber()) : messages.get("siteTransfer.receivedOne", unit.getUnitCode()));
        } catch (BusinessException e) {
            failed(id, "RECEIVE_SITE_TRANSFER", "receive a unit on", e, redirect);
        }
        redirect.addFlashAttribute("lastPlace", locationId);
        return "redirect:/site-transfers/" + id;
    }

    @PostMapping("/{id}/finish")
    @PreAuthorize("hasAuthority('PAGE_SITE_TRANSFERS') and hasAuthority('PERM_RECEIVE_SITE_TRANSFER')")
    public String finish(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        try {
            SiteTransfer t = transferService.finishShort(id, reason);
            log("RECEIVE_SITE_TRANSFER", "Received transfer " + t.getNumber() + " short: " + t.getDifferenceNote(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("siteTransfer.receivedShort", t.getNumber()));
        } catch (BusinessException e) {
            failed(id, "RECEIVE_SITE_TRANSFER", "finish", e, redirect);
        }
        return "redirect:/site-transfers/" + id;
    }

    private String withReason(UUID id, String reason, RedirectAttributes redirect, boolean reject) {
        String action = reject ? "REJECT_SITE_TRANSFER" : "CANCEL_SITE_TRANSFER";
        if (!StringUtils.hasText(reason) || reason.trim().length() > 255) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/site-transfers/" + id;
        }
        try {
            SiteTransfer t = AuditContext.withReason(reason.trim(),
                    () -> reject ? transferService.reject(id, reason) : transferService.cancel(id, reason));
            log(action, (reject ? "Rejected" : "Cancelled") + " transfer " + t.getNumber() + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(reject ? "siteTransfer.rejectedMsg" : "siteTransfer.cancelledMsg", t.getNumber()));
        } catch (BusinessException e) {
            failed(id, action, reject ? "reject" : "cancel", e, redirect);
        }
        return "redirect:/site-transfers/" + id;
    }

    private String form(Model model, SiteTransferDto dto) {
        model.addAttribute("transferDto", dto);
        model.addAttribute("sites", transferService.sites());
        model.addAttribute("products", productRepo.findByEnabledTrueOrderByCodeAsc());
        model.addAttribute("today", transferService.today());
        return "site-transfers/form";
    }

    private void log(String action, String text, ActivityStatus status) {
        activityLogService.record(MODULE, action, text, status);
    }

    private void failed(UUID id, String action, String verb, BusinessException e, RedirectAttributes redirect) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        String number;
        try {
            number = transferService.findDetailed(id).getNumber();
        } catch (RuntimeException ex) {
            number = id.toString();
        }
        log(action, "Failed to " + verb + " transfer " + number + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }
}
