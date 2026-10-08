package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.PurchaseOrderDto;
import com.ntaganira.heritier.iWarehouse.entity.GoodsReceipt;
import com.ntaganira.heritier.iWarehouse.entity.PurchaseOrder;
import com.ntaganira.heritier.iWarehouse.entity.PurchaseOrderLine;
import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.GoodsReceiptStatus;
import com.ntaganira.heritier.iWarehouse.enums.Incoterm;
import com.ntaganira.heritier.iWarehouse.enums.PurchaseOrderStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CurrencyRepository;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.GoodsReceiptService;
import com.ntaganira.heritier.iWarehouse.service.PurchaseOrderService;
import com.ntaganira.heritier.iWarehouse.service.PurchaseOrders;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : PurchaseOrderController.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Purchase order screens (PRC-01): list, detail with lines, receipts and History, add and
 *               edit drafts, place, cancel and close short (with a reason). PAGE_PROCUREMENT +
 *               PERM_VIEW_PURCHASE_ORDER; changes PERM_MANAGE_PURCHASE_ORDER.
 * </pre>
 */
@Controller
@RequestMapping("/purchase-orders")
public class PurchaseOrderController {

    static final String MODULE = "Purchase Orders";
    private static final int REASON_MAX = 255;

    private final PurchaseOrderService orderService;
    private final GoodsReceiptService receiptService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final CurrencyRepository currencyRepo;
    private final SpringValidatorAdapter validator;
    private final Messages messages;

    public PurchaseOrderController(PurchaseOrderService orderService, GoodsReceiptService receiptService,
                                   DataChangeService dataChangeService, ActivityLogService activityLogService,
                                   CurrencyRepository currencyRepo, Validator validator, Messages messages) {
        this.orderService = orderService;
        this.receiptService = receiptService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.currencyRepo = currencyRepo;
        this.validator = new SpringValidatorAdapter(validator);
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_PROCUREMENT') and hasAuthority('PERM_VIEW_PURCHASE_ORDER')")
    public String list(@RequestParam(required = false) String search,
                       @RequestParam(required = false) UUID supplier,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        Page<PurchaseOrder> orders = orderService.findPage(search, supplier, status, Paging.page(page), Paging.SIZE);
        model.addAttribute("orders", orders);
        model.addAttribute("totals", orderService.totals(orders.getContent()));
        model.addAttribute("decimals", currencyDecimals());
        model.addAttribute("supplierOptions", orderService.suppliersFor(null));
        model.addAttribute("statuses", PurchaseOrderStatus.values());
        model.addAttribute("search", search);
        model.addAttribute("supplier", supplier);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search,
                "supplier", supplier == null ? null : supplier.toString(), "status", status));
        return "purchase-orders/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_PROCUREMENT') and hasAuthority('PERM_VIEW_PURCHASE_ORDER')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "lines") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        PurchaseOrder order = orderService.findDetailed(id);
        List<GoodsReceipt> receipts = receiptService.receiptsOf(id);
        model.addAttribute("order", order);
        model.addAttribute("totals", orderService.totals(order));
        model.addAttribute("decimals", currencyDecimals().getOrDefault(order.getCurrencyCode(), 2));
        model.addAttribute("receipts", receipts);
        model.addAttribute("draftReceipts", receipts.stream().filter(r -> r.getStatus() == GoodsReceiptStatus.DRAFT).count());
        model.addAttribute("history", dataChangeService.historyWithChildren("PurchaseOrder", id.toString(),
                "PurchaseOrderLine", "purchaseOrder", Paging.page(page), Paging.SIZE));
        model.addAttribute("tab", List.of("lines", "receipts", "history").contains(tab) ? tab : "lines");
        return "purchase-orders/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_PROCUREMENT') and hasAuthority('PERM_MANAGE_PURCHASE_ORDER')")
    public String createForm(@RequestParam(required = false) UUID supplier, Model model) {
        PurchaseOrderDto dto = new PurchaseOrderDto();
        dto.setOrderDate(orderService.today());
        dto.setSupplierId(supplier);
        dto.getLines().add(new PurchaseOrderDto.Line());
        return form(model, dto);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_PROCUREMENT') and hasAuthority('PERM_MANAGE_PURCHASE_ORDER')")
    public String create(@ModelAttribute("poDto") PurchaseOrderDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        validate(dto, result);
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            PurchaseOrder order = orderService.create(dto);
            PurchaseOrders.Totals t = orderService.totals(orderService.findDetailed(order.getId()));
            activityLogService.record(MODULE, "CREATE_PURCHASE_ORDER", "Added purchase order " + order.getNumber()
                    + " for " + order.getSupplier().getName() + ": " + order.getLines().size() + " line(s), "
                    + t.sheets() + " sheets, " + t.amount().toPlainString() + " " + order.getCurrencyCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("po.created", order.getNumber()));
            return "redirect:/purchase-orders/" + order.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_PURCHASE_ORDER", "Failed to add a purchase order: "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PROCUREMENT') and hasAuthority('PERM_MANAGE_PURCHASE_ORDER')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        PurchaseOrder order = orderService.findDetailed(id);
        if (!order.getStatus().isEditable()) {
            redirect.addFlashAttribute("flashError", messages.get("po.notDraft", order.getNumber()));
            return "redirect:/purchase-orders/" + id;
        }
        PurchaseOrderDto dto = new PurchaseOrderDto();
        dto.setId(id);
        dto.setSupplierId(order.getSupplier().getId());
        dto.setOrderDate(order.getOrderDate());
        dto.setExpectedDate(order.getExpectedDate());
        dto.setIncoterm(order.getIncoterm());
        dto.setSupplierRef(order.getSupplierRef());
        dto.setNotes(order.getNotes());
        for (PurchaseOrderLine line : order.getLines()) {
            PurchaseOrderDto.Line row = new PurchaseOrderDto.Line();
            row.setId(line.getId());
            row.setProductId(line.getProduct().getId());
            row.setWidthMm(line.getWidthMm());
            row.setHeightMm(line.getHeightMm());
            row.setQuantity(line.getQuantity());
            row.setPricePerM2(line.getPricePerM2().stripTrailingZeros());
            dto.getLines().add(row);
        }
        return form(model, dto);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PROCUREMENT') and hasAuthority('PERM_MANAGE_PURCHASE_ORDER')")
    public String update(@PathVariable UUID id, @ModelAttribute("poDto") PurchaseOrderDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        dto.setId(id);
        validate(dto, result);
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            PurchaseOrder order = orderService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_PURCHASE_ORDER", "Updated purchase order " + order.getNumber()
                    + " (" + order.getSupplier().getName() + ", " + order.getLines().size() + " line(s))", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("po.updated", order.getNumber()));
            return "redirect:/purchase-orders/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_PURCHASE_ORDER", "Failed to update purchase order " + numberOf(id)
                    + ": " + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @PostMapping("/{id}/place")
    @PreAuthorize("hasAuthority('PAGE_PROCUREMENT') and hasAuthority('PERM_MANAGE_PURCHASE_ORDER')")
    public String place(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            PurchaseOrder order = orderService.place(id);
            PurchaseOrders.Totals t = orderService.totals(order);
            activityLogService.record(MODULE, "PLACE_PURCHASE_ORDER", "Placed purchase order " + order.getNumber()
                    + " with " + order.getSupplier().getName() + ": " + t.sheets() + " sheets, "
                    + t.amount().toPlainString() + " " + order.getCurrencyCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("po.placedMsg", order.getNumber(), order.getSupplier().getName()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "PLACE_PURCHASE_ORDER", "Failed to place purchase order " + numberOf(id)
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/purchase-orders/" + id;
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_PROCUREMENT') and hasAuthority('PERM_MANAGE_PURCHASE_ORDER')")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!reasonOk(reason, redirect)) {
            return "redirect:/purchase-orders/" + id;
        }
        try {
            PurchaseOrder order = AuditContext.withReason(reason.trim(), () -> orderService.cancel(id, reason));
            activityLogService.record(MODULE, "CANCEL_PURCHASE_ORDER", "Cancelled purchase order " + order.getNumber()
                    + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("po.cancelled", order.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CANCEL_PURCHASE_ORDER", "Failed to cancel purchase order " + numberOf(id)
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/purchase-orders/" + id;
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('PAGE_PROCUREMENT') and hasAuthority('PERM_MANAGE_PURCHASE_ORDER')")
    public String close(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!reasonOk(reason, redirect)) {
            return "redirect:/purchase-orders/" + id;
        }
        try {
            PurchaseOrder order = AuditContext.withReason(reason.trim(), () -> orderService.close(id, reason));
            PurchaseOrders.Totals t = orderService.totals(order);
            activityLogService.record(MODULE, "CLOSE_PURCHASE_ORDER", "Closed purchase order " + order.getNumber()
                    + " short, " + t.outstanding() + " sheets not delivered: " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("po.closed", order.getNumber(), t.outstanding()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CLOSE_PURCHASE_ORDER", "Failed to close purchase order " + numberOf(id)
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/purchase-orders/" + id;
    }

    // ---------------------------------------------------------------- helpers

    /** Drops empty rows, then runs Bean Validation (so a spare empty row is not an error). */
    private void validate(PurchaseOrderDto dto, BindingResult result) {
        dto.getLines().removeIf(PurchaseOrderDto.Line::isBlank);
        validator.validate(dto, result);
    }

    private boolean reasonOk(String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return false;
        }
        return true;
    }

    private String numberOf(UUID id) {
        try {
            return orderService.findById(id).getNumber();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }

    private Map<String, Integer> currencyDecimals() {
        Map<String, Integer> decimals = new HashMap<>();
        currencyRepo.findAll().forEach(c -> decimals.put(c.getCode(), c.getDecimals()));
        return decimals;
    }

    private String form(Model model, PurchaseOrderDto dto) {
        PurchaseOrder current = dto.getId() == null ? null : orderService.findDetailed(dto.getId());
        List<Supplier> suppliers = orderService.suppliersFor(current);
        model.addAttribute("poDto", dto);
        model.addAttribute("order", current);
        model.addAttribute("suppliers", suppliers);
        model.addAttribute("products", orderService.productsFor(current));
        model.addAttribute("incoterms", Incoterm.values());
        model.addAttribute("decimals", currencyDecimals());
        return "purchase-orders/form";
    }

    private String invalid(Model model, PurchaseOrderDto dto, BindingResult result) {
        if (dto.getLines().isEmpty()) {
            dto.getLines().add(new PurchaseOrderDto.Line());
        }
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    /** A business rule refused the form: show it next to its field, or as a toast. */
    private String rejected(Model model, PurchaseOrderDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }
}
