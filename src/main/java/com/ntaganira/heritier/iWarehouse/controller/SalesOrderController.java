package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.QuotationDto;
import com.ntaganira.heritier.iWarehouse.entity.Quotation;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.*;
import jakarta.validation.Validator;
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
 * - File      : SalesOrderController.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Sales orders raised by the Frontline Order Desk (BP 7.1, Annex G, F-01; decision D4): the list, the form
 *               (customer, channel, market, territory, agent, site, rows priced like a quotation), an order with the Order
 *               Desk's check of stock, prices and credit and its History, send it to the cashier, cancel it with a reason,
 *               make one from a sent quotation, and take its payment at the cashier's till. PAGE_SALES_ORDERS +
 *               PERM_VIEW_SALES_ORDER; changes PERM_CREATE_SALES_ORDER; the payment PAGE_POS + PERM_SELL.
 * </pre>
 */
@Controller
@RequestMapping("/sales-orders")
public class SalesOrderController {

    private static final int REASON_MAX = 255;
    private static final String VIEW = "hasAuthority('PAGE_SALES_ORDERS') and hasAuthority('PERM_VIEW_SALES_ORDER')";
    private static final String CREATE = "hasAuthority('PAGE_SALES_ORDERS') and hasAuthority('PERM_CREATE_SALES_ORDER')";

    private final QuotationService quotationService;
    private final SalesService salesService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;
    private final SpringValidatorAdapter validator;

    public SalesOrderController(QuotationService quotationService, SalesService salesService, DataChangeService dataChangeService,
                                ActivityLogService activityLogService, Messages messages, NumberFormats num, Validator validator) {
        this.quotationService = quotationService;
        this.salesService = salesService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
        this.validator = new SpringValidatorAdapter(validator);
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("orders", quotationService.findPage(QuotationKind.SALES_ORDER, status, search, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", List.of("DRAFT", "SENT", "EXPIRED", "CONVERTED", "CANCELLED"));
        model.addAttribute("today", quotationService.today());
        model.addAttribute("orderDesk", salesService.orderDesk());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "sales-orders/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "lines") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        String open = "history".equals(tab) ? tab : "lines";
        Quotation q = order(id);
        model.addAttribute("quotation", q);
        model.addAttribute("totals", quotationService.totals(q));
        model.addAttribute("today", quotationService.today());
        model.addAttribute("onTill", quotationService.onTill(q).orElse(null));
        model.addAttribute("invoiceNumber", quotationService.invoiceNumber(q).orElse(null));
        model.addAttribute("territory", q.getTerritoryId() == null ? null : quotationService.territoriesById().get(q.getTerritoryId()));
        model.addAttribute("agentName", q.getAgentId() == null ? null : quotationService.agents().stream()
                .filter(a -> a.getId().equals(q.getAgentId())).map(a -> a.getName()).findFirst().orElse(null));
        model.addAttribute("siteName", q.getSiteId() == null ? null : quotationService.sites().stream()
                .filter(s -> s.getId().equals(q.getSiteId())).map(s -> s.getName()).findFirst().orElse(null));
        model.addAttribute("sourceNumber", q.getSourceId() == null ? null : quotationService.findById(q.getSourceId()).getNumber());
        // The Order Desk's check while the order is open
        Map<UUID, QuotationService.LineStock> stockOf = new HashMap<>();
        if (q.isDraft() || q.isSent()) {
            QuotationService.DeskCheck check = quotationService.deskCheck(q);
            check.lines().forEach(l -> stockOf.put(l.line().getId(), l));
            model.addAttribute("check", check);
        }
        model.addAttribute("stockOf", stockOf);
        model.addAttribute("history", dataChangeService.historyWithChildren("Quotation", id.toString(), "QuotationLine",
                "quotation", Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "sales-orders/view";
    }

    @GetMapping("/new")
    @PreAuthorize(CREATE)
    public String createForm(Model model) {
        return form(model, quotationService.newForm(QuotationKind.SALES_ORDER), null);
    }

    @PostMapping("/new")
    @PreAuthorize(CREATE)
    public String create(@ModelAttribute("quoteDto") QuotationDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        validate(dto, result);
        if (refused(dto, result, model)) {
            return invalid(model, dto, result, null);
        }
        try {
            Quotation q = quotationService.create(QuotationKind.SALES_ORDER, dto, null);
            activityLogService.record(PosController.MODULE, "CREATE_SALES_ORDER", "Raised order " + q.getNumber() + " for "
                    + q.getBillTo() + " (" + q.getChannel() + "): " + describe(q), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("order.created", q.getNumber(), num.money(q.getTotalAmount())));
            return "redirect:/sales-orders/" + q.getId();
        } catch (BusinessException e) {
            activityLogService.record(PosController.MODULE, "CREATE_SALES_ORDER", "Failed to raise an order: "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e, null);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize(CREATE)
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        Quotation q = order(id);
        if (!q.isDraft()) {
            redirect.addFlashAttribute("flashError", messages.get("quote.notDraft", q.getNumber()));
            return "redirect:/sales-orders/" + id;
        }
        return form(model, quotationService.formOf(q, false), q);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize(CREATE)
    public String update(@PathVariable UUID id, @ModelAttribute("quoteDto") QuotationDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        dto.setId(id);
        Quotation current = order(id);
        validate(dto, result);
        if (refused(dto, result, model)) {
            return invalid(model, dto, result, current);
        }
        try {
            Quotation q = quotationService.update(id, dto);
            activityLogService.record(PosController.MODULE, "UPDATE_SALES_ORDER", "Updated order " + q.getNumber() + " for "
                    + q.getBillTo() + ": " + describe(q), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("order.updated", q.getNumber(), num.money(q.getTotalAmount())));
            return "redirect:/sales-orders/" + id;
        } catch (BusinessException e) {
            activityLogService.record(PosController.MODULE, "UPDATE_SALES_ORDER", "Failed to update order " + current.getNumber() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e, current);
        }
    }

    /** Sends the order to the cashier (Annex G step 3): fixed, to be paid by its day. */
    @PostMapping("/{id}/send")
    @PreAuthorize(CREATE)
    public String send(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            order(id);
            Quotation q = quotationService.send(id);
            activityLogService.record(PosController.MODULE, "SEND_SALES_ORDER", "Sent order " + q.getNumber() + " of " + q.getBillTo()
                    + " to the cashier: " + num.money(q.getTotalAmount()) + " RWF, to pay by " + q.getValidUntil(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("order.sent", q.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, "SEND_SALES_ORDER", "Failed to send order " + numberOf(id), e);
        }
        return "redirect:/sales-orders/" + id;
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(CREATE)
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/sales-orders/" + id;
        }
        try {
            order(id);
            Quotation q = AuditContext.withReason(reason.trim(), () -> quotationService.cancel(id, reason));
            activityLogService.record(PosController.MODULE, "CANCEL_SALES_ORDER", "Cancelled order " + q.getNumber() + ": " + reason.trim(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("order.cancelled", q.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, "CANCEL_SALES_ORDER", "Failed to cancel order " + numberOf(id), e);
        }
        return "redirect:/sales-orders/" + id;
    }

    /** The Order Desk makes an order of a sent quotation the customer accepts. */
    @PostMapping("/from/{quotationId}")
    @PreAuthorize(CREATE)
    public String fromQuotation(@PathVariable UUID quotationId, RedirectAttributes redirect) {
        try {
            Quotation q = quotationService.orderFrom(quotationId);
            activityLogService.record(PosController.MODULE, "CREATE_SALES_ORDER", "Raised order " + q.getNumber() + " from quotation "
                    + numberOf(quotationId) + ": " + describe(q), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("order.fromQuotation", q.getNumber(), numberOf(quotationId)));
            return "redirect:/sales-orders/" + q.getId();
        } catch (BusinessException e) {
            fail(redirect, "CREATE_SALES_ORDER", "Failed to raise an order from quotation " + numberOf(quotationId), e);
            return "redirect:/quotations/" + quotationId;
        }
    }

    /** The cashier takes the payment: the order becomes the sale of their till (Annex G step 4). */
    @PostMapping("/{id}/ring-up")
    @PreAuthorize("hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String ringUp(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            order(id);
            SalesInvoice sale = salesService.ringUp(id);
            String number = numberOf(id);
            activityLogService.record(PosController.MODULE, "UPDATE_SALE", "Rang up order " + number + " at till "
                    + sale.getTillSession().getNumber() + ": " + sale.getLines().size() + " line(s)", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("order.rungUp", number));
            return "redirect:/pos";
        } catch (BusinessException e) {
            fail(redirect, "UPDATE_SALE", "Failed to ring up order " + numberOf(id), e);
            return "redirect:/sales-orders/" + id;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** An order (a quotation of kind SALES_ORDER); a quotation is not found here. */
    private Quotation order(UUID id) {
        Quotation q = quotationService.findDetailed(id);
        if (!q.isOrder()) {
            throw new NotFoundException("SalesOrder", id);
        }
        return q;
    }

    private String describe(Quotation q) {
        long rows = q.getLines().stream().filter(l -> !l.isServiceLine()).count();
        return rows + " line(s), " + num.money(q.getTotalAmount()) + " RWF, to pay by " + q.getValidUntil();
    }

    private void validate(QuotationDto dto, BindingResult result) {
        dto.getLines().removeIf(QuotationDto.Line::isBlank);
        validator.validate(dto, result);
    }

    private boolean refused(QuotationDto dto, BindingResult result, Model model) {
        if (!result.hasErrors() && dto.getLines().isEmpty()) {
            model.addAttribute("flashError", messages.get("quote.lines.required"));
            return true;
        }
        return result.hasErrors();
    }

    private String form(Model model, QuotationDto dto, Quotation current) {
        model.addAttribute("quoteDto", dto);
        model.addAttribute("quotation", current);
        model.addAttribute("customers", quotationService.customers());
        model.addAttribute("products", quotationService.products());
        model.addAttribute("services", quotationService.services());
        model.addAttribute("kinds", List.of(QuoteLineKind.CUSTOM_PIECE, QuoteLineKind.SHEET));
        model.addAttribute("discountLimit", salesService.discountLimit());
        model.addAttribute("channels", OrderChannel.values());
        model.addAttribute("markets", Market.values());
        model.addAttribute("territories", quotationService.territories());
        model.addAttribute("agents", quotationService.agents());
        model.addAttribute("sites", quotationService.sites());
        return "sales-orders/form";
    }

    private String invalid(Model model, QuotationDto dto, BindingResult result, Quotation current) {
        if (dto.getLines().isEmpty()) {
            dto.getLines().add(new QuotationDto.Line());
        }
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto, current);
    }

    private String rejected(Model model, QuotationDto dto, BindingResult result, BusinessException e, Quotation current) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result, current);
    }

    private void fail(RedirectAttributes redirect, String action, String what, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        activityLogService.record(PosController.MODULE, action, what + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }

    private String numberOf(UUID id) {
        try {
            return quotationService.findById(id).getNumber();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }
}
