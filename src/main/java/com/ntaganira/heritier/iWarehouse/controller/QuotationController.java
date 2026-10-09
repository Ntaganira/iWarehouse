package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.QuotationDto;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.Quotation;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.QuoteLineKind;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
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

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : QuotationController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Quotations (POS-03): the list (expired ones shown), the form (whole sheets and sizes to cut with
 *               processing, discount per row), a quotation with its lines, VAT and History, its print page (A4),
 *               send, cancel with a reason, copy into a new one, and ring it up at the cashier's till.
 *               PAGE_QUOTATIONS + PERM_VIEW_QUOTATION; changes PERM_MANAGE_QUOTATION; ringing up PERM_SELL too.
 * </pre>
 */
@Controller
@RequestMapping("/quotations")
public class QuotationController {

    private static final int REASON_MAX = 255;

    private final QuotationService quotationService;
    private final SalesService salesService;
    private final DataChangeService dataChangeService;
    private final SettingService settingService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;
    private final SpringValidatorAdapter validator;

    public QuotationController(QuotationService quotationService, SalesService salesService, DataChangeService dataChangeService,
                               SettingService settingService, ActivityLogService activityLogService, Messages messages,
                               NumberFormats num, Validator validator) {
        this.quotationService = quotationService;
        this.salesService = salesService;
        this.dataChangeService = dataChangeService;
        this.settingService = settingService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
        this.validator = new SpringValidatorAdapter(validator);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_QUOTATIONS') and hasAuthority('PERM_VIEW_QUOTATION')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("quotations", quotationService.findPage(status, search, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", List.of("DRAFT", "SENT", "EXPIRED", "CONVERTED", "CANCELLED"));
        model.addAttribute("today", quotationService.today());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "quotations/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_QUOTATIONS') and hasAuthority('PERM_VIEW_QUOTATION')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "lines") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        String open = "history".equals(tab) ? tab : "lines";
        Quotation q = quotationService.findDetailed(id);
        model.addAttribute("quotation", q);
        model.addAttribute("totals", quotationService.totals(q));
        model.addAttribute("today", quotationService.today());
        model.addAttribute("onTill", quotationService.onTill(q).orElse(null));
        model.addAttribute("invoiceNumber", quotationService.invoiceNumber(q).orElse(null));
        model.addAttribute("history", dataChangeService.historyWithChildren("Quotation", id.toString(), "QuotationLine",
                "quotation", Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "quotations/view";
    }

    /** The quotation to print or save as PDF from the browser (A4). */
    @GetMapping("/{id}/print")
    @PreAuthorize("hasAuthority('PAGE_QUOTATIONS') and hasAuthority('PERM_VIEW_QUOTATION')")
    public String print(@PathVariable UUID id, Model model) {
        Quotation q = quotationService.findDetailed(id);
        model.addAttribute("quotation", q);
        model.addAttribute("totals", quotationService.totals(q));
        model.addAttribute("companyName", settingService.get(SettingKey.COMPANY_NAME));
        model.addAttribute("companyTin", settingService.get(SettingKey.COMPANY_TIN));
        model.addAttribute("companyAddress", settingService.get(SettingKey.COMPANY_ADDRESS));
        model.addAttribute("companyPhone", settingService.get(SettingKey.COMPANY_PHONE));
        model.addAttribute("companyEmail", settingService.get(SettingKey.COMPANY_EMAIL));
        activityLogService.record(PosController.MODULE, "PRINT_QUOTATION", "Opened quotation " + q.getNumber() + " for printing",
                ActivityStatus.SUCCESS);
        return "quotations/print";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_QUOTATIONS') and hasAuthority('PERM_MANAGE_QUOTATION')")
    public String createForm(@RequestParam(required = false) UUID from, Model model) {
        QuotationDto dto = from == null ? quotationService.newForm() : quotationService.formOf(quotationService.findDetailed(from), true);
        return form(model, dto, null);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_QUOTATIONS') and hasAuthority('PERM_MANAGE_QUOTATION')")
    public String create(@ModelAttribute("quoteDto") QuotationDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        validate(dto, result);
        if (refused(dto, result, model)) {
            return invalid(model, dto, result, null);
        }
        try {
            Quotation q = quotationService.create(dto);
            activityLogService.record(PosController.MODULE, "CREATE_QUOTATION", "Added quotation " + q.getNumber() + " for "
                    + q.getBillTo() + ": " + describe(q), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("quote.created", q.getNumber(), num.money(q.getTotalAmount())));
            return "redirect:/quotations/" + q.getId();
        } catch (BusinessException e) {
            activityLogService.record(PosController.MODULE, "CREATE_QUOTATION", "Failed to add a quotation: "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e, null);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_QUOTATIONS') and hasAuthority('PERM_MANAGE_QUOTATION')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        Quotation q = quotationService.findDetailed(id);
        if (!q.isDraft()) {
            redirect.addFlashAttribute("flashError", messages.get("quote.notDraft", q.getNumber()));
            return "redirect:/quotations/" + id;
        }
        return form(model, quotationService.formOf(q, false), q);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_QUOTATIONS') and hasAuthority('PERM_MANAGE_QUOTATION')")
    public String update(@PathVariable UUID id, @ModelAttribute("quoteDto") QuotationDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        dto.setId(id);
        Quotation current = quotationService.findById(id);
        validate(dto, result);
        if (refused(dto, result, model)) {
            return invalid(model, dto, result, current);
        }
        try {
            Quotation q = quotationService.update(id, dto);
            activityLogService.record(PosController.MODULE, "UPDATE_QUOTATION", "Updated quotation " + q.getNumber() + " for "
                    + q.getBillTo() + ": " + describe(q), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("quote.updated", q.getNumber(), num.money(q.getTotalAmount())));
            return "redirect:/quotations/" + id;
        } catch (BusinessException e) {
            activityLogService.record(PosController.MODULE, "UPDATE_QUOTATION", "Failed to update quotation " + current.getNumber() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e, current);
        }
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasAuthority('PAGE_QUOTATIONS') and hasAuthority('PERM_MANAGE_QUOTATION')")
    public String send(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            Quotation q = quotationService.send(id);
            activityLogService.record(PosController.MODULE, "SEND_QUOTATION", "Sent quotation " + q.getNumber() + " to " + q.getBillTo()
                    + ": " + num.money(q.getTotalAmount()) + " RWF, valid until " + q.getValidUntil(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("quote.sent", q.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, "SEND_QUOTATION", "Failed to send quotation " + numberOf(id), e);
        }
        return "redirect:/quotations/" + id;
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_QUOTATIONS') and hasAuthority('PERM_MANAGE_QUOTATION')")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/quotations/" + id;
        }
        try {
            Quotation q = AuditContext.withReason(reason.trim(), () -> quotationService.cancel(id, reason));
            activityLogService.record(PosController.MODULE, "CANCEL_QUOTATION", "Cancelled quotation " + q.getNumber() + ": "
                    + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("quote.cancelled", q.getNumber()));
        } catch (BusinessException e) {
            fail(redirect, "CANCEL_QUOTATION", "Failed to cancel quotation " + numberOf(id), e);
        }
        return "redirect:/quotations/" + id;
    }

    /** Rings the quotation up at the signed-in cashier's till: it becomes the sale being rung up. */
    @PostMapping("/{id}/ring-up")
    @PreAuthorize("hasAuthority('PAGE_QUOTATIONS') and hasAuthority('PAGE_POS') and hasAuthority('PERM_SELL')")
    public String ringUp(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            SalesInvoice sale = salesService.ringUp(id);
            String number = numberOf(id);
            activityLogService.record(PosController.MODULE, "UPDATE_SALE", "Rang up quotation " + number + " at till "
                    + sale.getTillSession().getNumber() + ": " + sale.getLines().size() + " line(s)", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("quote.rungUp", number));
            return "redirect:/pos";
        } catch (BusinessException e) {
            fail(redirect, "UPDATE_SALE", "Failed to ring up quotation " + numberOf(id), e);
            return "redirect:/quotations/" + id;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** "3 line(s), 412,500 RWF, valid until 2026-10-23". */
    private String describe(Quotation q) {
        long rows = q.getLines().stream().filter(l -> !l.isServiceLine()).count();
        return rows + " line(s), " + num.money(q.getTotalAmount()) + " RWF, valid until " + q.getValidUntil();
    }

    /** Drops empty rows, then runs Bean Validation (so a spare empty row is not an error). */
    private void validate(QuotationDto dto, BindingResult result) {
        dto.getLines().removeIf(QuotationDto.Line::isBlank);
        validator.validate(dto, result);
    }

    /** Field errors, or no row left: the form again (a quotation needs at least one row). */
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
        List<Product> products = quotationService.products();
        model.addAttribute("products", products);
        model.addAttribute("services", quotationService.services());
        model.addAttribute("kinds", List.of(QuoteLineKind.CUSTOM_PIECE, QuoteLineKind.SHEET));
        model.addAttribute("discountLimit", salesService.discountLimit());
        return "quotations/form";
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
