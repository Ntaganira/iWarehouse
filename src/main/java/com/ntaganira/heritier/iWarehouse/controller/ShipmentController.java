package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.ShipmentDto;
import com.ntaganira.heritier.iWarehouse.entity.Shipment;
import com.ntaganira.heritier.iWarehouse.entity.ShipmentCost;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.ShipmentService;
import jakarta.validation.Validator;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : ShipmentController.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Shipment screens (PRC-03..06): list, cost sheet with bills, crates and landed cost per m²,
 *               claim and History; add and edit (receipts, draft bills), post the draft bills, close,
 *               cancel with a reason, and the broken-on-arrival claim. PAGE_SHIPMENTS +
 *               PERM_VIEW_SHIPMENT; changes PERM_MANAGE_SHIPMENT; posting PERM_POST_LANDED_COST.
 * </pre>
 */
@Controller
@RequestMapping("/shipments")
public class ShipmentController {

    static final String MODULE = "Shipments";
    private static final int REASON_MAX = 255;

    private final ShipmentService shipmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final SpringValidatorAdapter validator;
    private final Messages messages;
    private final NumberFormats num;

    public ShipmentController(ShipmentService shipmentService, DataChangeService dataChangeService,
                              ActivityLogService activityLogService, Validator validator, Messages messages,
                              NumberFormats num) {
        this.shipmentService = shipmentService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.validator = new SpringValidatorAdapter(validator);
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_SHIPMENTS') and hasAuthority('PERM_VIEW_SHIPMENT')")
    public String list(@RequestParam(required = false) String search,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        Page<Shipment> shipments = shipmentService.findPage(search, status, Paging.page(page), Paging.SIZE);
        model.addAttribute("shipments", shipments);
        model.addAttribute("totals", shipmentService.totals(shipments.getContent()));
        model.addAttribute("statuses", ShipmentStatus.values());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "shipments/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_SHIPMENTS') and hasAuthority('PERM_VIEW_SHIPMENT')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "costs") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        ShipmentService.CostSheet sheet = shipmentService.costSheet(id);
        model.addAttribute("sheet", sheet);
        model.addAttribute("shipment", sheet.shipment());
        model.addAttribute("base", shipmentService.baseCurrency());
        model.addAttribute("today", shipmentService.today());
        model.addAttribute("history", dataChangeService.historyWithChildren("Shipment", id.toString(),
                List.of("ShipmentCost", "ShipmentReceipt"), "shipment", Paging.page(page), Paging.SIZE));
        model.addAttribute("tab", List.of("costs", "crates", "claim", "history").contains(tab) ? tab : "costs");
        return "shipments/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_SHIPMENTS') and hasAuthority('PERM_MANAGE_SHIPMENT')")
    public String createForm(Model model) {
        return form(model, shipmentService.newForm(), null);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_SHIPMENTS') and hasAuthority('PERM_MANAGE_SHIPMENT')")
    public String create(@ModelAttribute("shipmentDto") ShipmentDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        validate(dto, result);
        if (result.hasErrors()) {
            return invalid(model, dto, null, result);
        }
        try {
            Shipment shipment = shipmentService.create(dto);
            activityLogService.record(MODULE, "CREATE_SHIPMENT", "Created shipment " + shipment.getNumber()
                    + ": " + describe(shipment), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("shipment.created", shipment.getNumber()));
            return "redirect:/shipments/" + shipment.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_SHIPMENT", "Failed to create a shipment: "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, null, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_SHIPMENTS') and hasAuthority('PERM_MANAGE_SHIPMENT')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        Shipment shipment = shipmentService.findDetailed(id);
        if (shipment.getStatus() != ShipmentStatus.OPEN) {
            redirect.addFlashAttribute("flashError", messages.get("shipment.notOpen", shipment.getNumber()));
            return "redirect:/shipments/" + id;
        }
        ShipmentDto dto = new ShipmentDto();
        dto.setId(id);
        dto.setReference(shipment.getReference());
        dto.setArrivalDate(shipment.getArrivalDate());
        dto.setAllocationMethod(shipment.getAllocationMethod());
        dto.setNotes(shipment.getNotes());
        shipment.getReceipts().forEach(r -> dto.getReceiptIds().add(r.getGoodsReceipt().getId()));
        for (ShipmentCost cost : shipment.getCosts()) {
            if (cost.getStatus() != ShipmentCostStatus.DRAFT) {
                continue;
            }
            ShipmentDto.Cost row = new ShipmentDto.Cost();
            row.setId(cost.getId());
            row.setCostType(cost.getCostType());
            row.setDescription(cost.getDescription());
            row.setSupplierId(cost.getSupplier() == null ? null : cost.getSupplier().getId());
            row.setInvoiceRef(cost.getInvoiceRef());
            row.setInvoiceDate(cost.getInvoiceDate());
            row.setCurrencyCode(cost.getCurrencyCode());
            row.setAmount(cost.getAmount());
            dto.getCosts().add(row);
        }
        return form(model, dto, shipment);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_SHIPMENTS') and hasAuthority('PERM_MANAGE_SHIPMENT')")
    public String update(@PathVariable UUID id, @ModelAttribute("shipmentDto") ShipmentDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        Shipment current = shipmentService.findDetailed(id);
        dto.setId(id);
        validate(dto, result);
        if (result.hasErrors()) {
            return invalid(model, dto, current, result);
        }
        try {
            Shipment shipment = shipmentService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_SHIPMENT", "Updated shipment " + shipment.getNumber()
                    + ": " + describe(shipment), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("shipment.updated", shipment.getNumber()));
            return "redirect:/shipments/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_SHIPMENT", "Failed to update shipment " + current.getNumber()
                    + ": " + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, current, result, e);
        }
    }

    @PostMapping("/{id}/post")
    @PreAuthorize("hasAuthority('PAGE_SHIPMENTS') and hasAuthority('PERM_POST_LANDED_COST')")
    public String post(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            ShipmentService.PostResult result = shipmentService.post(id);
            Shipment shipment = result.shipment();
            String bills = shipment.getCosts().stream()
                    .filter(c -> c.getPostingNo() != null && c.getPostingNo() == result.postingNo())
                    .map(c -> c.getCostType() + " " + c.getCurrencyCode() + " " + c.getAmount().toPlainString()
                            + (c.getRateSource() == null ? "" : " at " + c.getRate().stripTrailingZeros().toPlainString()
                            + " (" + c.getRateSource() + " " + c.getRateDate() + ")"))
                    .collect(Collectors.joining("; "));
            activityLogService.record(MODULE, "POST_SHIPMENT_COSTS", "Posted " + result.lines() + " import cost(s) on "
                    + shipment.getNumber() + " (posting " + result.postingNo() + ", by " + shipment.getAllocationMethod()
                    + "): " + rwf(result.total()) + " RWF; " + rwf(result.toStock()) + " added to " + result.units()
                    + " units in stock, " + rwf(result.expensed()) + " expensed, " + rwf(result.broken())
                    + " on sheets broken on arrival. " + bills, ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("shipment.posted", result.lines(),
                    shipment.getNumber(), num.money(result.total()), result.units()));
            return "redirect:/shipments/" + id + "?tab=crates";
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "POST_SHIPMENT_COSTS", "Failed to post import costs on " + numberOf(id)
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
            return "redirect:/shipments/" + id;
        }
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('PAGE_SHIPMENTS') and hasAuthority('PERM_MANAGE_SHIPMENT')")
    public String close(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            Shipment shipment = shipmentService.close(id);
            activityLogService.record(MODULE, "CLOSE_SHIPMENT", "Closed shipment " + shipment.getNumber()
                    + ": import costs complete", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("shipment.closedMsg", shipment.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CLOSE_SHIPMENT", "Failed to close shipment " + numberOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/shipments/" + id;
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_SHIPMENTS') and hasAuthority('PERM_MANAGE_SHIPMENT')")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/shipments/" + id;
        }
        try {
            Shipment shipment = AuditContext.withReason(reason.trim(), () -> shipmentService.cancel(id, reason));
            activityLogService.record(MODULE, "CANCEL_SHIPMENT", "Cancelled shipment " + shipment.getNumber()
                    + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("shipment.cancelledMsg", shipment.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CANCEL_SHIPMENT", "Failed to cancel shipment " + numberOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/shipments/" + id;
    }

    // ---------------------------------------------------------------- claim (PRC-06)

    @PostMapping("/{id}/claim")
    @PreAuthorize("hasAuthority('PAGE_SHIPMENTS') and hasAuthority('PERM_MANAGE_SHIPMENT')")
    public String openClaim(@PathVariable UUID id, @RequestParam(required = false) String party,
                            @RequestParam(required = false) String ref,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                            @RequestParam(required = false) BigDecimal amount, RedirectAttributes redirect) {
        try {
            Shipment shipment = shipmentService.openClaim(id, party, ref, date, amount);
            activityLogService.record(MODULE, "CREATE_SHIPMENT_CLAIM", "Claimed " + rwf(shipment.getClaimAmount())
                    + " RWF from " + shipment.getClaimParty() + " for sheets broken on arrival in " + shipment.getNumber()
                    + (shipment.getClaimRef() == null ? "" : " (ref " + shipment.getClaimRef() + ")"), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("shipment.claim.opened", shipment.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_SHIPMENT_CLAIM", "Failed to record the claim on " + numberOf(id)
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/shipments/" + id + "?tab=claim";
    }

    @PostMapping("/{id}/claim/settle")
    @PreAuthorize("hasAuthority('PAGE_SHIPMENTS') and hasAuthority('PERM_MANAGE_SHIPMENT')")
    public String settleClaim(@PathVariable UUID id, @RequestParam(required = false) BigDecimal received,
                              @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            Shipment shipment = shipmentService.settleClaim(id, received, note);
            activityLogService.record(MODULE, "SETTLE_SHIPMENT_CLAIM", "Settled the claim on " + shipment.getNumber()
                    + ": " + rwf(shipment.getClaimSettledAmount()) + " RWF received of " + rwf(shipment.getClaimAmount())
                    + " claimed" + (shipment.getClaimNote() == null ? "" : " (" + shipment.getClaimNote() + ")"),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("shipment.claim.settledMsg", shipment.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "SETTLE_SHIPMENT_CLAIM", "Failed to settle the claim on " + numberOf(id)
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/shipments/" + id + "?tab=claim";
    }

    @PostMapping("/{id}/claim/reject")
    @PreAuthorize("hasAuthority('PAGE_SHIPMENTS') and hasAuthority('PERM_MANAGE_SHIPMENT')")
    public String rejectClaim(@PathVariable UUID id, @RequestParam(required = false) String reason,
                              RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/shipments/" + id + "?tab=claim";
        }
        try {
            Shipment shipment = AuditContext.withReason(reason.trim(), () -> shipmentService.rejectClaim(id, reason));
            activityLogService.record(MODULE, "REJECT_SHIPMENT_CLAIM", "Claim on " + shipment.getNumber()
                    + " rejected by " + shipment.getClaimParty() + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("shipment.claim.rejectedMsg", shipment.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "REJECT_SHIPMENT_CLAIM", "Failed to record the rejected claim on "
                    + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/shipments/" + id + "?tab=claim";
    }

    // ---------------------------------------------------------------- helpers

    /** "MSKU 123456-7, 2 receipt(s), 3 draft bill(s), by AREA". */
    private static String describe(Shipment shipment) {
        long drafts = shipment.getCosts().stream().filter(c -> c.getStatus() == ShipmentCostStatus.DRAFT).count();
        String receipts = shipment.getReceipts().stream().map(r -> r.getReceiptNumber()).sorted()
                .collect(Collectors.joining(", "));
        return (shipment.getReference() == null ? "" : shipment.getReference() + ", ")
                + shipment.getReceipts().size() + " receipt(s)" + (receipts.isEmpty() ? "" : " (" + receipts + ")")
                + ", " + drafts + " draft bill(s), by " + shipment.getAllocationMethod();
    }

    private String rwf(BigDecimal amount) {
        return num.money(amount);
    }

    /** Drops empty rows, then runs Bean Validation (so a spare empty row is not an error). */
    private void validate(ShipmentDto dto, BindingResult result) {
        dto.getCosts().removeIf(ShipmentDto.Cost::isBlank);
        dto.getReceiptIds().removeIf(java.util.Objects::isNull);
        validator.validate(dto, result);
    }

    private String numberOf(UUID id) {
        try {
            return shipmentService.findById(id).getNumber();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }

    private String form(Model model, ShipmentDto dto, Shipment current) {
        if (dto.getCosts().isEmpty()) {
            dto.getCosts().add(shipmentService.blankCost());
        }
        model.addAttribute("shipmentDto", dto);
        model.addAttribute("shipment", current);
        model.addAttribute("postedCosts", current == null ? List.of()
                : current.getCosts().stream().filter(c -> c.getStatus() == ShipmentCostStatus.POSTED).toList());
        model.addAttribute("locked", current != null && current.hasPostedCosts());
        model.addAttribute("receipts", shipmentService.availableReceipts(current));
        model.addAttribute("suppliers", shipmentService.suppliersFor(current));
        model.addAttribute("currencies", shipmentService.currencies());
        model.addAttribute("base", shipmentService.baseCurrency());
        model.addAttribute("methods", AllocationMethod.values());
        model.addAttribute("costTypes", CostType.values());
        return "shipments/form";
    }

    private String invalid(Model model, ShipmentDto dto, Shipment current, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        if (result.hasGlobalErrors()) {
            model.addAttribute("flashError", result.getGlobalError().getDefaultMessage());
        }
        return form(model, dto, current);
    }

    /** A business rule refused the form: show it next to its field, or as a toast. */
    private String rejected(Model model, ShipmentDto dto, Shipment current, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, current, result);
    }
}
