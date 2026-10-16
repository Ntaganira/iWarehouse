package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.AdvanceRetireDto;
import com.ntaganira.heritier.iWarehouse.dto.StaffAdvanceDto;
import com.ntaganira.heritier.iWarehouse.entity.StaffAdvance;
import com.ntaganira.heritier.iWarehouse.enums.*;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
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
import java.util.function.Supplier;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : StaffAdvanceController.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Staff advances (CMP 08): the list (waiting, overdue), the form of a draft (a journey worked out from its per
 *               diem, or another amount), the advance with its receipts, journals and history, its steps: submit, approve
 *               (within the Delegation of Authority), reject, cancel, pay (PERM_PAY_ADVANCE) and retire with what it paid for
 *               (PERM_RETIRE_ADVANCE). Each needs PAGE_STAFF_ADVANCES.
 * </pre>
 */
@Controller
@RequestMapping("/payroll/advances")
public class StaffAdvanceController {

    private static final String MODULE = "Payroll";
    private static final String BASE = "/payroll/advances";
    private static final String VIEW = "hasAuthority('PAGE_STAFF_ADVANCES') and hasAuthority('PERM_VIEW_ADVANCE')";
    private static final String REQUEST = "hasAuthority('PAGE_STAFF_ADVANCES') and hasAuthority('PERM_REQUEST_ADVANCE')";
    private static final String APPROVE = "hasAuthority('PAGE_STAFF_ADVANCES') and hasAuthority('PERM_APPROVE_ADVANCE')";
    private static final String RETIRE = "hasAuthority('PAGE_STAFF_ADVANCES') and hasAuthority('PERM_RETIRE_ADVANCE')";

    private final StaffAdvanceService advances;
    private final PerDiemService perDiem;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final SpringValidatorAdapter validator;
    private final Messages messages;
    private final NumberFormats num;

    public StaffAdvanceController(StaffAdvanceService advances, PerDiemService perDiem, AttachmentService attachmentService,
                                  DataChangeService dataChangeService, ActivityLogService activityLogService, SpringValidatorAdapter validator,
                                  Messages messages, NumberFormats num) {
        this.advances = advances;
        this.perDiem = perDiem;
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
        model.addAttribute("advances", advances.findPage(search, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", StaffAdvanceStatus.values());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("waiting", advances.waitingCount());
        model.addAttribute("overdue", advances.overdueCount());
        model.addAttribute("today", advances.today());
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "advances/list";
    }

    @GetMapping("/new")
    @PreAuthorize(REQUEST)
    public String create(@RequestParam(required = false) UUID employee, Model model) {
        return form(model, advances.newForm(employee), null);
    }

    @PostMapping("/new")
    @PreAuthorize(REQUEST)
    public String create(@ModelAttribute("advanceDto") StaffAdvanceDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        return save(null, dto, result, model, redirect);
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize(REQUEST)
    public String edit(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        StaffAdvance a = advances.findDetailed(id);
        if (!a.isDraft()) {
            redirect.addFlashAttribute("flashError", messages.get("advance.notDraft", a.getNumber()));
            return "redirect:" + BASE + "/" + id;
        }
        return form(model, advances.formOf(a), a);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize(REQUEST)
    public String edit(@PathVariable UUID id, @ModelAttribute("advanceDto") StaffAdvanceDto dto, BindingResult result, Model model,
                       RedirectAttributes redirect) {
        return save(id, dto, result, model, redirect);
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(required = false) String tab, @RequestParam(defaultValue = "0") int page, Model model) {
        StaffAdvance a = advances.findDetailed(id);
        String open = "history".equals(tab) ? tab : "documents";
        model.addAttribute("advance", a);
        model.addAttribute("journals", advances.journals(a));
        model.addAttribute("mine", advances.isRequester(a));
        model.addAttribute("ownAdvance", advances.isEmployee(a));
        model.addAttribute("authority", a.getStatus() == StaffAdvanceStatus.SUBMITTED ? advances.authorityOf(a) : null);
        model.addAttribute("overdue", a.isOverdue(advances.today()));
        model.addAttribute("payMethods", AdvancePayMethod.values());
        model.addAttribute("funds", advances.funds());
        model.addAttribute("today", advances.today());
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.STAFF_ADVANCE, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.historyWithChildren("StaffAdvance", id.toString(), "StaffAdvanceLine", "advance",
                Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "advances/view";
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(REQUEST)
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        return step(id, redirect, "SUBMIT_ADVANCE", "Submitted", "advance.submitted", () -> advances.submit(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVE)
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        return step(id, redirect, "APPROVE_ADVANCE", "Approved", "advance.approved", () -> advances.approve(id, note));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(APPROVE)
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return step(id, redirect, "REJECT_ADVANCE", "Rejected", "advance.rejected",
                () -> AuditContext.withReason(clean(reason), () -> advances.reject(id, reason)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(REQUEST)
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return step(id, redirect, "CANCEL_ADVANCE", "Cancelled", "advance.cancelled",
                () -> AuditContext.withReason(clean(reason), () -> advances.cancel(id, reason)));
    }

    @PostMapping("/{id}/pay")
    @PreAuthorize("hasAuthority('PAGE_STAFF_ADVANCES') and hasAuthority('PERM_PAY_ADVANCE')")
    public String pay(@PathVariable UUID id, @RequestParam(required = false) AdvancePayMethod payMethod, @RequestParam(required = false) UUID payFundId,
                      @RequestParam(required = false) String payReference,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paidOn, RedirectAttributes redirect) {
        return step(id, redirect, "PAY_ADVANCE", "Paid", "advance.paidDone", () -> advances.pay(id, payMethod, payFundId, payReference, paidOn));
    }

    @GetMapping("/{id}/retire")
    @PreAuthorize(RETIRE)
    public String retire(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        StaffAdvance a = advances.findDetailed(id);
        if (!a.isPaid()) {
            redirect.addFlashAttribute("flashError", messages.get("advance.notPaid", a.getNumber()));
            return "redirect:" + BASE + "/" + id;
        }
        AdvanceRetireDto dto = new AdvanceRetireDto();
        dto.setRetiredOn(advances.today());
        AdvanceRetireDto.Line line = new AdvanceRetireDto.Line();
        line.setCostCentreId(a.getCostCentre().getId());
        line.setCategory(a.getKind() == StaffAdvanceKind.TRAVEL ? ExpenseCategory.TRAVEL : null);
        dto.getLines().add(line);
        return retireForm(model, dto, a);
    }

    @PostMapping("/{id}/retire")
    @PreAuthorize(RETIRE)
    public String retire(@PathVariable UUID id, @ModelAttribute("retireDto") AdvanceRetireDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        StaffAdvance a = advances.findDetailed(id);
        dto.getLines().removeIf(AdvanceRetireDto.Line::isBlank);
        validator.validate(dto, result);
        if (dto.getLines().isEmpty()) {
            result.reject("advance.retire.lines", messages.get("advance.retire.lines"));
        }
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            if (result.hasGlobalErrors()) {
                model.addAttribute("flashError", messages.get("advance.retire.lines"));
            }
            return retireForm(model, dto, a);
        }
        try {
            StaffAdvance done = advances.retire(id, dto);
            activityLogService.record(MODULE, "RETIRE_ADVANCE", "Retired advance " + done.getNumber() + " of " + done.getEmployee().getFullName() + ": spent "
                    + num.money(done.getSpent()) + " RWF, " + (done.getSettled().signum() >= 0 ? "returned " : "paid back ")
                    + num.money(done.getSettled().abs()) + " RWF", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("advance.retired", done.getNumber()));
            return "redirect:" + BASE + "/" + id;
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "RETIRE_ADVANCE", "Failed to retire advance " + a.getNumber() + ": " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
                model.addAttribute("formErrors", result.getFieldErrors());
            } else {
                model.addAttribute("flashError", error);
            }
            return retireForm(model, dto, a);
        }
    }

    // ---------------------------------------------------------------- helpers

    private String save(UUID id, StaffAdvanceDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        validator.validate(dto, result);
        StaffAdvance existing = id == null ? null : advances.findDetailed(id);
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto, existing);
        }
        try {
            StaffAdvance a = advances.save(id, dto);
            activityLogService.record(MODULE, id == null ? "CREATE_ADVANCE" : "UPDATE_ADVANCE", (id == null ? "Asked for" : "Changed") + " advance "
                    + a.getNumber() + " to " + a.getEmployee().getFullName() + ": " + num.money(a.getAmount()) + " RWF for " + a.getPurpose(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("advance.saved", a.getNumber()));
            return "redirect:" + BASE + "/" + a.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, id == null ? "CREATE_ADVANCE" : "UPDATE_ADVANCE", "Failed to save a staff advance: " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
                model.addAttribute("formErrors", result.getFieldErrors());
            } else {
                model.addAttribute("flashError", error);
            }
            return form(model, dto, existing);
        }
    }

    private String form(Model model, StaffAdvanceDto dto, StaffAdvance advance) {
        model.addAttribute("advanceDto", dto);
        model.addAttribute("advance", advance);
        model.addAttribute("employees", advances.employees());
        model.addAttribute("costCentres", advances.costCentres());
        model.addAttribute("kinds", StaffAdvanceKind.values());
        model.addAttribute("categories", PerDiemCategory.values());
        model.addAttribute("rates", perDiem.rates());
        return "advances/form";
    }

    private String retireForm(Model model, AdvanceRetireDto dto, StaffAdvance advance) {
        model.addAttribute("retireDto", dto);
        model.addAttribute("advance", advance);
        model.addAttribute("categories", advances.categories());
        model.addAttribute("costCentres", advances.costCentres());
        model.addAttribute("payMethods", AdvancePayMethod.values());
        model.addAttribute("funds", advances.funds());
        model.addAttribute("receipts", attachmentService.count(AttachmentOwner.STAFF_ADVANCE, advance.getId()));
        model.addAttribute("today", advances.today());
        return "advances/retire";
    }

    /** Runs one step of an advance and logs it, a refusal too. */
    private String step(UUID id, RedirectAttributes redirect, String action, String verb, String successKey, Supplier<StaffAdvance> run) {
        try {
            StaffAdvance a = run.get();
            String note = a.getDecisionNote() != null && ("REJECT_ADVANCE".equals(action) || "CANCEL_ADVANCE".equals(action)) ? ": " + a.getDecisionNote() : "";
            activityLogService.record(MODULE, action, verb + " advance " + a.getNumber() + " to " + a.getEmployee().getFullName() + ", "
                    + num.money(a.getAmount()) + " RWF" + note, ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(successKey, a.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed: " + verb.toLowerCase() + " advance " + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:" + BASE + "/" + id;
    }

    private String numberOf(UUID id) {
        try {
            return advances.findDetailed(id).getNumber();
        } catch (RuntimeException e) {
            return id.toString();
        }
    }

    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
