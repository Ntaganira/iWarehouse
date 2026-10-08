package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.StockAdjustmentDto;
import com.ntaganira.heritier.iWarehouse.entity.StockAdjustment;
import com.ntaganira.heritier.iWarehouse.entity.StockAdjustmentLine;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.StockAdjustmentService;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : StockAdjustmentController.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Adjustment screens (INV-07): list, adjustment page with its lines, approval and History,
 *               the form (also opened from a unit's page: write off, correct size, found again), approve,
 *               reject with a reason, withdraw with a reason. PAGE_STOCK_ADJUSTMENTS +
 *               PERM_VIEW_STOCK_ADJUSTMENT; new PERM_ADJUST_STOCK; approve and reject PERM_APPROVE_ADJUSTMENT.
 * </pre>
 */
@Controller
@RequestMapping("/stock-adjustments")
public class StockAdjustmentController {

    static final String MODULE = "Stock Adjustments";
    private static final int REASON_MAX = 255;

    private final StockAdjustmentService adjustmentService;
    private final StockService stockService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final SpringValidatorAdapter validator;
    private final Messages messages;
    private final NumberFormats num;

    public StockAdjustmentController(StockAdjustmentService adjustmentService, StockService stockService,
                                     DataChangeService dataChangeService, ActivityLogService activityLogService,
                                     Validator validator, Messages messages, NumberFormats num) {
        this.adjustmentService = adjustmentService;
        this.stockService = stockService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.validator = new SpringValidatorAdapter(validator);
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_STOCK_ADJUSTMENTS') and hasAuthority('PERM_VIEW_STOCK_ADJUSTMENT')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        Page<StockAdjustment> adjustments = adjustmentService.findPage(search, status, Paging.page(page), Paging.SIZE);
        model.addAttribute("adjustments", adjustments);
        model.addAttribute("statuses", AdjustmentStatus.values());
        model.addAttribute("pending", adjustmentService.pendingCount());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "stock-adjustments/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_STOCK_ADJUSTMENTS') and hasAuthority('PERM_VIEW_STOCK_ADJUSTMENT')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "lines") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        StockAdjustment adjustment = adjustmentService.findDetailed(id);
        model.addAttribute("adjustment", adjustment);
        model.addAttribute("units", adjustmentService.unitsOf(adjustment));
        model.addAttribute("limit", adjustmentService.approvalLimit());
        model.addAttribute("mine", isMine(adjustment));
        model.addAttribute("history", dataChangeService.historyWithChildren("StockAdjustment", id.toString(),
                List.of("StockAdjustmentLine"), "adjustment", Paging.page(page), Paging.SIZE));
        model.addAttribute("tab", "history".equals(tab) ? tab : "lines");
        return "stock-adjustments/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_STOCK_ADJUSTMENTS') and hasAuthority('PERM_ADJUST_STOCK')")
    public String createForm(@RequestParam(required = false) UUID unit, @RequestParam(required = false) AdjustmentKind kind,
                             Model model) {
        StockUnit u = unit == null ? null : stockService.findDetailed(unit);
        return form(model, adjustmentService.newForm(u, kind));
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_STOCK_ADJUSTMENTS') and hasAuthority('PERM_ADJUST_STOCK')")
    public String create(@ModelAttribute("adjustmentDto") StockAdjustmentDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        dto.getLines().removeIf(StockAdjustmentDto.Line::isBlank);
        validator.validate(dto, result);
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            StockAdjustment adjustment = AuditContext.withReason(dto.getReason().trim(), () -> adjustmentService.create(dto));
            boolean posted = adjustment.getStatus() == AdjustmentStatus.POSTED;
            activityLogService.record(MODULE, "CREATE_STOCK_ADJUSTMENT", (posted ? "Posted" : "Asked approval for")
                    + " adjustment " + adjustment.getNumber() + ": " + describe(adjustment), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(posted ? "adjustment.posted" : "adjustment.pendingMsg",
                    adjustment.getNumber(), num.money(adjustment.getValueMoved())));
            return "redirect:/stock-adjustments/" + adjustment.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_STOCK_ADJUSTMENT", "Failed to adjust stock: " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
            } else {
                model.addAttribute("flashError", error);
            }
            return invalid(model, dto, result);
        }
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PAGE_STOCK_ADJUSTMENTS') and hasAuthority('PERM_APPROVE_ADJUSTMENT')")
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            StockAdjustment adjustment = adjustmentService.approve(id, note);
            activityLogService.record(MODULE, "APPROVE_STOCK_ADJUSTMENT", "Approved adjustment " + adjustment.getNumber()
                    + " of " + adjustment.getRequestedBy() + ": " + describe(adjustment), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("adjustment.approved", adjustment.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "APPROVE_STOCK_ADJUSTMENT", "Failed to approve adjustment " + numberOf(id)
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/stock-adjustments/" + id;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAGE_STOCK_ADJUSTMENTS') and hasAuthority('PERM_APPROVE_ADJUSTMENT')")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return decideWithReason(id, reason, redirect, true);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_STOCK_ADJUSTMENTS') and hasAuthority('PERM_ADJUST_STOCK')")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return decideWithReason(id, reason, redirect, false);
    }

    private String decideWithReason(UUID id, String reason, RedirectAttributes redirect, boolean reject) {
        String action = reject ? "REJECT_STOCK_ADJUSTMENT" : "CANCEL_STOCK_ADJUSTMENT";
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/stock-adjustments/" + id;
        }
        try {
            StockAdjustment adjustment = AuditContext.withReason(reason.trim(),
                    () -> reject ? adjustmentService.reject(id, reason) : adjustmentService.cancel(id, reason));
            activityLogService.record(MODULE, action, (reject ? "Rejected" : "Withdrew") + " adjustment "
                    + adjustment.getNumber() + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(reject ? "adjustment.rejectedMsg" : "adjustment.cancelledMsg",
                    adjustment.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to " + (reject ? "reject" : "withdraw") + " adjustment "
                    + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/stock-adjustments/" + id;
    }

    // ---------------------------------------------------------------- helpers

    /** "RWF -252,629 moved 252,629: write-off U-WH-000036 (DAMAGED); new unit CLR-6 1000x500 at WH-A-R01". */
    private String describe(StockAdjustment adjustment) {
        String lines = adjustment.getLines().stream().map(StockAdjustmentController::describe).collect(Collectors.joining("; "));
        return "value " + num.money(adjustment.getValueChange()) + " RWF (moved " + num.money(adjustment.getValueMoved())
                + "), reason: " + adjustment.getReason() + "; " + lines;
    }

    private static String describe(StockAdjustmentLine l) {
        return switch (l.getKind()) {
            case WRITE_OFF -> "write-off " + l.getUnitCode() + " (" + l.getCause() + ")";
            case FOUND -> "found " + l.getUnitCode() + " at " + l.getLocation().getCode();
            case NEW_UNIT -> "new " + l.getUnitKind() + " " + l.getProduct().getCode() + " " + l.getWidthMm() + "x"
                    + l.getHeightMm() + " at " + l.getLocation().getCode();
            case RESIZE -> "resize " + l.getUnitCode() + " to " + l.getWidthMm() + "x" + l.getHeightMm();
        } + " " + l.getValueChange().toPlainString();
    }

    private static boolean isMine(StockAdjustment adjustment) {
        return AppUserPrincipal.current()
                .map(u -> adjustment.getRequestedById() != null ? adjustment.getRequestedById().equals(u.getId())
                        : adjustment.getRequestedBy().equals(u.getUsername()))
                .orElse(false);
    }

    private String numberOf(UUID id) {
        try {
            return adjustmentService.findById(id).getNumber();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }

    private String form(Model model, StockAdjustmentDto dto) {
        if (dto.getLines().isEmpty()) {
            StockAdjustmentDto.Line row = new StockAdjustmentDto.Line();
            row.setKind(AdjustmentKind.WRITE_OFF);
            dto.getLines().add(row);
        }
        model.addAttribute("adjustmentDto", dto);
        model.addAttribute("kinds", AdjustmentKind.values());
        model.addAttribute("causes", WriteOffCause.values());
        model.addAttribute("unitKinds", UnitKind.values());
        model.addAttribute("products", adjustmentService.products());
        model.addAttribute("places", adjustmentService.places());
        BigDecimal limit = adjustmentService.approvalLimit();
        model.addAttribute("limit", limit);
        return "stock-adjustments/form";
    }

    private String invalid(Model model, StockAdjustmentDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }
}
