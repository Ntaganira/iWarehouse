package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.ExpenseBillDto;
import com.ntaganira.heritier.iWarehouse.entity.ExpenseBill;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseBillStatus;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseCategory;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.AttachmentService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.ExpenseBillService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : ExpenseBillController.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Expense bills (CMP 01, 05, 07): the list (waiting, to ratify), the form of a draft (lines by category, cost
 *               centre and location), the bill with its documents, journal and history, and its steps: submit, recommend,
 *               review, approve (within the expenditure matrix), reject, cancel, ratify (the Board) and reverse. Each needs
 *               PAGE_EXPENSE_BILLS and its own permission.
 * </pre>
 */
@Controller
@RequestMapping("/expenses")
public class ExpenseBillController {

    static final String MODULE = "Expenditure";
    private static final String BASE = "/expenses";
    private static final String VIEW = "hasAuthority('PAGE_EXPENSE_BILLS') and hasAuthority('PERM_VIEW_EXPENSE')";
    private static final String CREATE = "hasAuthority('PAGE_EXPENSE_BILLS') and hasAuthority('PERM_CREATE_EXPENSE')";

    private final ExpenseBillService billService;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final SpringValidatorAdapter validator;
    private final Messages messages;
    private final NumberFormats num;

    public ExpenseBillController(ExpenseBillService billService, AttachmentService attachmentService, DataChangeService dataChangeService,
                                 ActivityLogService activityLogService, SpringValidatorAdapter validator, Messages messages, NumberFormats num) {
        this.billService = billService;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.validator = validator;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("bills", billService.findPage(search, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", ExpenseBillStatus.values());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("waiting", billService.waitingCount());
        model.addAttribute("toRatify", billService.toRatifyCount());
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "expenses/list";
    }

    @GetMapping("/new")
    @PreAuthorize(CREATE)
    public String create(Model model) {
        return form(model, billService.newForm(), null);
    }

    @PostMapping("/new")
    @PreAuthorize(CREATE)
    public String create(@ModelAttribute("billDto") ExpenseBillDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        return save(null, dto, result, model, redirect);
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize(CREATE)
    public String edit(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        ExpenseBill bill = billService.findDetailed(id);
        if (!bill.isDraft()) {
            redirect.addFlashAttribute("flashError", messages.get("expense.notDraft", bill.getNumber()));
            return "redirect:" + BASE + "/" + id;
        }
        return form(model, billService.formOf(bill), bill);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize(CREATE)
    public String edit(@PathVariable UUID id, @ModelAttribute("billDto") ExpenseBillDto dto, BindingResult result, Model model,
                       RedirectAttributes redirect) {
        return save(id, dto, result, model, redirect);
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(required = false) String tab, @RequestParam(defaultValue = "0") int page, Model model) {
        ExpenseBill bill = billService.findDetailed(id);
        String open = "history".equals(tab) || "journal".equals(tab) ? tab : "documents";
        model.addAttribute("bill", bill);
        model.addAttribute("journals", billService.journals(bill));
        model.addAttribute("mine", billService.isEnterer(bill));
        model.addAttribute("tookPart", billService.tookPart(bill));
        model.addAttribute("step", billService.nextStep(bill));
        model.addAttribute("authority", bill.isWaiting() ? billService.authorityOf(bill) : null);
        model.addAttribute("decision", billService.decisionOf(bill));
        model.addAttribute("requisitionRequired", billService.requisitionRequired(bill));
        model.addAttribute("locationCodes", billService.locations().stream()
                .collect(java.util.stream.Collectors.toMap(com.ntaganira.heritier.iWarehouse.entity.Location::getId,
                        com.ntaganira.heritier.iWarehouse.entity.Location::getCode)));
        model.addAttribute("today", billService.today());
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.EXPENSE_BILL, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.historyWithChildren("ExpenseBill", id.toString(), "ExpenseBillLine", "bill",
                Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "expenses/view";
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(CREATE)
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        return step(id, redirect, "SUBMIT_EXPENSE", "Submitted", "expense.submitted", b -> billService.submit(id));
    }

    @PostMapping("/{id}/recommend")
    @PreAuthorize("hasAuthority('PAGE_EXPENSE_BILLS') and hasAuthority('PERM_RECOMMEND_EXPENSE')")
    public String recommend(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        return step(id, redirect, "APPROVE_EXPENSE_RECOMMEND", "Recommended", "expense.recommended", b -> billService.recommend(id, note));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('PAGE_EXPENSE_BILLS') and hasAuthority('PERM_REVIEW_EXPENSE')")
    public String review(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        return step(id, redirect, "APPROVE_EXPENSE_REVIEW", "Reviewed", "expense.reviewed", b -> billService.review(id, note));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PAGE_EXPENSE_BILLS') and hasAuthority('PERM_APPROVE_EXPENSE')")
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        return step(id, redirect, "APPROVE_EXPENSE", "Approved", "expense.approved", b -> billService.approve(id, note));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAGE_EXPENSE_BILLS') and (hasAuthority('PERM_APPROVE_EXPENSE') or hasAuthority('PERM_REVIEW_EXPENSE')"
            + " or hasAuthority('PERM_RECOMMEND_EXPENSE'))")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return step(id, redirect, "REJECT_EXPENSE", "Rejected", "expense.rejected",
                b -> AuditContext.withReason(clean(reason), () -> billService.reject(id, reason)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(CREATE)
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return step(id, redirect, "CANCEL_EXPENSE", "Cancelled", "expense.cancelled",
                b -> AuditContext.withReason(clean(reason), () -> billService.cancel(id, reason)));
    }

    @PostMapping("/{id}/ratify")
    @PreAuthorize("hasAuthority('PAGE_EXPENSE_BILLS') and hasAuthority('PERM_RATIFY_EXPENSE')")
    public String ratify(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        return step(id, redirect, "APPROVE_EXPENSE_RATIFY", "Ratified", "expense.ratified", b -> billService.ratify(id, note));
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasAuthority('PAGE_EXPENSE_BILLS') and hasAuthority('PERM_REVERSE_EXPENSE')")
    public String reverse(@PathVariable UUID id, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reversalDate,
                          @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return step(id, redirect, "REVERSE_EXPENSE", "Reversed", "expense.reversed",
                b -> AuditContext.withReason(clean(reason), () -> billService.reverse(id, reversalDate, reason)));
    }

    // ---------------------------------------------------------------- helpers

    private String save(UUID id, ExpenseBillDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        dto.getLines().removeIf(ExpenseBillDto.Line::isBlank);
        validator.validate(dto, result);
        ExpenseBill existing = id == null ? null : billService.findDetailed(id);
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto, existing);
        }
        try {
            ExpenseBill bill = billService.save(id, dto);
            activityLogService.record(MODULE, id == null ? "CREATE_EXPENSE" : "UPDATE_EXPENSE", (id == null ? "Entered" : "Changed")
                    + " expense bill " + bill.getNumber() + " of " + bill.getSupplier().getName() + " (" + bill.getSupplierRef() + "), "
                    + num.money(bill.getAmount()) + " RWF: " + bill.getDescription(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("expense.saved", bill.getNumber()));
            return "redirect:" + BASE + "/" + bill.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, id == null ? "CREATE_EXPENSE" : "UPDATE_EXPENSE", "Failed to save an expense bill: " + error,
                    ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
                model.addAttribute("formErrors", result.getFieldErrors());
            } else {
                model.addAttribute("flashError", error);
            }
            return form(model, dto, existing);
        }
    }

    private String form(Model model, ExpenseBillDto dto, ExpenseBill bill) {
        model.addAttribute("billDto", dto);
        model.addAttribute("bill", bill);
        model.addAttribute("suppliers", billService.suppliers());
        model.addAttribute("costCentres", billService.costCentres());
        model.addAttribute("locations", billService.locations());
        model.addAttribute("categories", ExpenseCategory.values());
        model.addAttribute("requisitions", billService.requisitions(dto.getRequisitionId()));
        model.addAttribute("today", billService.today());
        return "expenses/form";
    }

    /** Runs one step of a bill and logs it, a refusal too. */
    private String step(UUID id, RedirectAttributes redirect, String action, String verb, String successKey, Function<Void, ExpenseBill> run) {
        try {
            ExpenseBill bill = run.apply(null);
            String note = bill.getDecisionNote() != null && ("REJECT_EXPENSE".equals(action) || "CANCEL_EXPENSE".equals(action)) ? ": " + bill.getDecisionNote()
                    : bill.getReversalReason() != null && "REVERSE_EXPENSE".equals(action) ? ": " + bill.getReversalReason() : "";
            activityLogService.record(MODULE, action, verb + " expense bill " + bill.getNumber() + " of " + bill.getSupplier().getName() + ", "
                    + num.money(bill.getAmount()) + " RWF" + note, ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(successKey, bill.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed: " + verb.toLowerCase() + " expense bill " + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:" + BASE + "/" + id;
    }

    private String numberOf(UUID id) {
        try {
            return billService.findDetailed(id).getNumber();
        } catch (RuntimeException e) {
            return id.toString();
        }
    }

    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
