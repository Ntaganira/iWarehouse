package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.dto.GoodsReceiptDto;
import com.ntaganira.heritier.iWarehouse.entity.CrateBatch;
import com.ntaganira.heritier.iWarehouse.entity.GoodsReceipt;
import com.ntaganira.heritier.iWarehouse.entity.PurchaseOrder;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.GoodsReceiptStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.GoodsReceiptService;
import com.ntaganira.heritier.iWarehouse.service.PurchaseOrderService;
import com.ntaganira.heritier.iWarehouse.service.ShipmentService;
import com.ntaganira.heritier.iWarehouse.service.StockService;
import jakarta.validation.Validator;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : GoodsReceiptController.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Goods receipt screens (PRC-02, PRC-06): list, detail with crates, units and History,
 *               record crates against a placed order (draft), edit, post into stock, cancel a draft
 *               with a reason. PAGE_RECEIVING + PERM_VIEW_GOODS_RECEIPT; changes PERM_RECEIVE_GOODS.
 * </pre>
 */
@Controller
@RequestMapping("/goods-receipts")
public class GoodsReceiptController {

    static final String MODULE = "Goods Receipts";
    private static final int PAGE_SIZE = 20;
    private static final int REASON_MAX = 255;

    private final GoodsReceiptService receiptService;
    private final PurchaseOrderService orderService;
    private final StockService stockService;
    private final ShipmentService shipmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final SpringValidatorAdapter validator;
    private final Messages messages;

    public GoodsReceiptController(GoodsReceiptService receiptService, PurchaseOrderService orderService,
                                  StockService stockService, ShipmentService shipmentService,
                                  DataChangeService dataChangeService,
                                  ActivityLogService activityLogService, Validator validator, Messages messages) {
        this.receiptService = receiptService;
        this.orderService = orderService;
        this.stockService = stockService;
        this.shipmentService = shipmentService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.validator = new SpringValidatorAdapter(validator);
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_RECEIVING') and hasAuthority('PERM_VIEW_GOODS_RECEIPT')")
    public String list(@RequestParam(required = false) String search,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        Page<GoodsReceipt> receipts = receiptService.findPage(search, status, Math.max(page, 0), PAGE_SIZE);
        model.addAttribute("receipts", receipts);
        model.addAttribute("totals", receiptService.totals(receipts.getContent()));
        model.addAttribute("statuses", GoodsReceiptStatus.values());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "goods-receipts/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_RECEIVING') and hasAuthority('PERM_VIEW_GOODS_RECEIPT')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "crates") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        GoodsReceipt receipt = receiptService.findDetailed(id);
        model.addAttribute("receipt", receipt);
        model.addAttribute("units", receipt.getStatus() == GoodsReceiptStatus.POSTED ? stockService.unitsOfReceipt(id) : List.of());
        // The shipment it came in and the landed cost per m² posted to its crates (PRC-05).
        model.addAttribute("landing", shipmentService.landingOf(receipt).orElse(null));
        model.addAttribute("history", dataChangeService.historyWithChildren("GoodsReceipt", id.toString(),
                "CrateBatch", "goodsReceipt", Math.max(page, 0), 20));
        model.addAttribute("tab", List.of("crates", "units", "history").contains(tab) ? tab : "crates");
        return "goods-receipts/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_RECEIVING') and hasAuthority('PERM_RECEIVE_GOODS')")
    public String createForm(@RequestParam UUID po, Model model, RedirectAttributes redirect) {
        PurchaseOrder order = orderService.findDetailed(po);
        if (!order.getStatus().isReceivable()) {
            redirect.addFlashAttribute("flashError", messages.get("receipt.order.notReceivable", order.getNumber()));
            return "redirect:/purchase-orders/" + po;
        }
        return form(model, receiptService.newForm(order), order, null);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_RECEIVING') and hasAuthority('PERM_RECEIVE_GOODS')")
    public String create(@ModelAttribute("receiptDto") GoodsReceiptDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (dto.getPurchaseOrderId() == null) {
            throw new NotFoundException("PurchaseOrder", null);
        }
        PurchaseOrder order = orderService.findDetailed(dto.getPurchaseOrderId());
        validate(dto, result);
        if (result.hasErrors()) {
            return invalid(model, dto, order, null, result);
        }
        try {
            GoodsReceipt receipt = receiptService.create(order.getId(), dto);
            activityLogService.record(MODULE, "CREATE_GOODS_RECEIPT", "Recorded draft goods receipt " + receipt.getNumber()
                    + " against " + order.getNumber() + ": " + describe(receipt), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("receipt.created", receipt.getNumber()));
            return "redirect:/goods-receipts/" + receipt.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_GOODS_RECEIPT", "Failed to record a goods receipt against "
                    + order.getNumber() + ": " + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, order, null, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_RECEIVING') and hasAuthority('PERM_RECEIVE_GOODS')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        GoodsReceipt receipt = receiptService.findDetailed(id);
        if (receipt.getStatus() != GoodsReceiptStatus.DRAFT) {
            redirect.addFlashAttribute("flashError", messages.get("receipt.notDraft", receipt.getNumber()));
            return "redirect:/goods-receipts/" + id;
        }
        GoodsReceiptDto dto = new GoodsReceiptDto();
        dto.setId(id);
        dto.setPurchaseOrderId(receipt.getPurchaseOrder().getId());
        dto.setReceivedDate(receipt.getReceivedDate());
        dto.setDeliveryRef(receipt.getDeliveryRef());
        dto.setNotes(receipt.getNotes());
        for (CrateBatch crate : receipt.getCrates()) {
            GoodsReceiptDto.Crate row = new GoodsReceiptDto.Crate();
            row.setId(crate.getId());
            row.setPoLineId(crate.getPoLine().getId());
            row.setBatchNo(crate.getBatchNo());
            row.setWidthMm(crate.getWidthMm());
            row.setHeightMm(crate.getHeightMm());
            row.setSheets(crate.getSheets());
            row.setBroken(crate.getBroken());
            row.setLocationId(crate.getLocation().getId());
            dto.getCrates().add(row);
        }
        return form(model, dto, orderService.findDetailed(dto.getPurchaseOrderId()), receipt);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_RECEIVING') and hasAuthority('PERM_RECEIVE_GOODS')")
    public String update(@PathVariable UUID id, @ModelAttribute("receiptDto") GoodsReceiptDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        GoodsReceipt current = receiptService.findDetailed(id);
        PurchaseOrder order = orderService.findDetailed(current.getPurchaseOrder().getId());
        dto.setId(id);
        dto.setPurchaseOrderId(order.getId());
        validate(dto, result);
        if (result.hasErrors()) {
            return invalid(model, dto, order, current, result);
        }
        try {
            GoodsReceipt receipt = receiptService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_GOODS_RECEIPT", "Updated draft goods receipt " + receipt.getNumber()
                    + ": " + describe(receipt), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("receipt.updated", receipt.getNumber()));
            return "redirect:/goods-receipts/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_GOODS_RECEIPT", "Failed to update goods receipt " + current.getNumber()
                    + ": " + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, order, current, result, e);
        }
    }

    @PostMapping("/{id}/post")
    @PreAuthorize("hasAuthority('PAGE_RECEIVING') and hasAuthority('PERM_RECEIVE_GOODS')")
    public String post(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            GoodsReceiptService.PostResult result = receiptService.post(id);
            GoodsReceipt receipt = result.receipt();
            String racks = receipt.getCrates().stream().map(c -> c.getLocation().getCode()).distinct().sorted()
                    .collect(Collectors.joining(", "));
            String rate = result.rate().source() == null ? "base currency"
                    : "rate " + result.rate().currencyCode() + " " + result.rate().rate().stripTrailingZeros().toPlainString() + " ("
                    + result.rate().source() + " " + result.rate().rateDate() + ")";
            activityLogService.record(MODULE, "POST_GOODS_RECEIPT", "Posted goods receipt " + receipt.getNumber()
                    + " against " + receipt.getPurchaseOrder().getNumber() + ": " + result.units() + " stock units on "
                    + racks + ", " + receipt.getBroken() + " broken on arrival, " + rate, ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("receipt.posted", receipt.getNumber(), result.units()));
            return "redirect:/goods-receipts/" + id + "?tab=units";
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "POST_GOODS_RECEIPT", "Failed to post goods receipt " + numberOf(id)
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
            return "redirect:/goods-receipts/" + id;
        }
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_RECEIVING') and hasAuthority('PERM_RECEIVE_GOODS')")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/goods-receipts/" + id;
        }
        try {
            GoodsReceipt receipt = AuditContext.withReason(reason.trim(), () -> receiptService.cancel(id, reason));
            activityLogService.record(MODULE, "CANCEL_GOODS_RECEIPT", "Cancelled draft goods receipt " + receipt.getNumber()
                    + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("receipt.cancelled", receipt.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CANCEL_GOODS_RECEIPT", "Failed to cancel goods receipt " + numberOf(id)
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/goods-receipts/" + id;
    }

    // ---------------------------------------------------------------- helpers

    /** "2 crate(s), 38 sheets, 2 broken". */
    private static String describe(GoodsReceipt receipt) {
        return receipt.getCrates().size() + " crate(s), " + receipt.getSheets() + " sheets, " + receipt.getBroken() + " broken";
    }

    /** Drops empty rows, then runs Bean Validation (so a spare empty row is not an error). */
    private void validate(GoodsReceiptDto dto, BindingResult result) {
        dto.getCrates().removeIf(GoodsReceiptDto.Crate::isBlank);
        validator.validate(dto, result);
        if (dto.getCrates().isEmpty() && !result.hasErrors()) {
            result.reject("receipt.crates.required", messages.get("receipt.crates.required"));
        }
    }

    private String numberOf(UUID id) {
        try {
            return receiptService.findById(id).getNumber();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }

    private String form(Model model, GoodsReceiptDto dto, PurchaseOrder order, GoodsReceipt current) {
        if (dto.getCrates().isEmpty()) {
            dto.getCrates().add(new GoodsReceiptDto.Crate());
        }
        model.addAttribute("receiptDto", dto);
        model.addAttribute("order", order);
        model.addAttribute("receipt", current);
        model.addAttribute("racks", receiptService.rackChoices());
        return "goods-receipts/form";
    }

    private String invalid(Model model, GoodsReceiptDto dto, PurchaseOrder order, GoodsReceipt current, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        if (result.hasGlobalErrors()) {
            model.addAttribute("flashError", result.getGlobalError().getDefaultMessage());
        }
        return form(model, dto, order, current);
    }

    /** A business rule refused the form: show it next to its field, or as a toast. */
    private String rejected(Model model, GoodsReceiptDto dto, PurchaseOrder order, GoodsReceipt current,
                            BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, order, current, result);
    }
}
