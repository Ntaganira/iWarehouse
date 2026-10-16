package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.BudgetDto;
import com.ntaganira.heritier.iWarehouse.dto.BudgetLinesDto;
import com.ntaganira.heritier.iWarehouse.dto.BudgetRevisionDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.BudgetService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.RoundingMode;
import java.util.*;
import java.util.function.Supplier;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : BudgetController.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Budgets (CMP 04): the years' budgets, a budget with its lines, departments, revisions and history; its
 *               header; a department's (or the company's) lines; the steps: a department submitted or sent back, the budget
 *               submitted to the Board, approved with the resolution, sent back, cancelled; a line's revision asked, and a
 *               revision approved within the Delegation of Authority, rejected or cancelled. Each needs PAGE_BUDGETS.
 * </pre>
 */
@Controller
@RequestMapping("/budgets")
public class BudgetController {

    private static final String MODULE = "Budget";
    private static final String VIEW = "hasAuthority('PAGE_BUDGETS') and hasAuthority('PERM_VIEW_BUDGET')";
    private static final String PREPARE = "hasAuthority('PAGE_BUDGETS') and hasAuthority('PERM_PREPARE_BUDGET')";
    private static final String LINES = "hasAuthority('PAGE_BUDGETS') and (hasAuthority('PERM_PREPARE_BUDGET') or hasAuthority('PERM_SUBMIT_BUDGET'))";
    private static final String APPROVE = "hasAuthority('PAGE_BUDGETS') and hasAuthority('PERM_APPROVE_BUDGET')";
    private static final String REVISE = "hasAuthority('PAGE_BUDGETS') and hasAuthority('PERM_REVISE_BUDGET')";
    private static final String DECIDE_REVISION = "hasAuthority('PAGE_BUDGETS') and hasAuthority('PERM_APPROVE_BUDGET_REVISION')";

    private final BudgetService budgets;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public BudgetController(BudgetService budgets, DataChangeService dataChangeService, ActivityLogService activityLogService, Messages messages,
                            NumberFormats num) {
        this.budgets = budgets;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize(VIEW)
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        List<Budget> all = budgets.all();
        Map<UUID, BudgetService.Totals> totals = new HashMap<>();
        all.forEach(b -> totals.put(b.getId(), budgets.totals(budgets.lines(b.getId()))));
        model.addAttribute("budgets", Paging.of(all, Paging.page(page)));
        model.addAttribute("totals", totals);
        model.addAttribute("waiting", budgets.waitingCount());
        model.addAttribute("revisionsWaiting", budgets.revisionsWaiting());
        return "budgets/list";
    }

    @GetMapping("/new")
    @PreAuthorize(PREPARE)
    public String create(Model model) {
        return form(model, budgets.newForm(), null);
    }

    @PostMapping("/new")
    @PreAuthorize(PREPARE)
    public String create(@Valid @ModelAttribute("budgetDto") BudgetDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        return saveHeader(null, dto, result, model, redirect);
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize(PREPARE)
    public String edit(@PathVariable UUID id, Model model) {
        Budget b = budgets.findDetailed(id);
        return form(model, budgets.formOf(b), b);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize(PREPARE)
    public String edit(@PathVariable UUID id, @Valid @ModelAttribute("budgetDto") BudgetDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        return saveHeader(id, dto, result, model, redirect);
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(required = false) String tab, @RequestParam(defaultValue = "0") int page, Model model) {
        Budget b = budgets.findDetailed(id);
        String open = "departments".equals(tab) || "revisions".equals(tab) || "history".equals(tab) ? tab : "lines";
        List<BudgetLine> lines = budgets.lines(id);
        List<BudgetDepartment> departments = budgets.departments(id);
        Map<UUID, java.math.BigDecimal> deptTotals = new HashMap<>();
        Map<UUID, Long> deptLines = new HashMap<>();
        lines.stream().filter(l -> l.getCostCentre() != null).forEach(l -> {
            deptTotals.merge(l.getCostCentre().getId(), l.getTotal(), java.math.BigDecimal::add);
            deptLines.merge(l.getCostCentre().getId(), 1L, Long::sum);
        });
        model.addAttribute("budget", b);
        model.addAttribute("totals", budgets.totals(lines));
        model.addAttribute("lines", Paging.of(lines, Paging.pageOf("lines", open, page)));
        model.addAttribute("departments", Paging.of(departments, Paging.pageOf("departments", open, page)));
        model.addAttribute("deptTotals", deptTotals);
        model.addAttribute("deptLines", deptLines);
        model.addAttribute("companyLines", lines.stream().filter(l -> l.getCostCentre() == null).count());
        model.addAttribute("openDepartments", departments.stream().filter(d -> deptLines.containsKey(d.getCostCentre().getId()) && !d.isSubmitted())
                .map(d -> d.getCostCentre().getCode()).toList());
        model.addAttribute("revisions", budgets.revisions(id, Paging.pageOf("revisions", open, page), Paging.SIZE));
        model.addAttribute("revisionsWaiting", budgets.revisionsWaiting(id));
        model.addAttribute("own", budgets.isOwn(b));
        model.addAttribute("authority", b.getStatus() == com.ntaganira.heritier.iWarehouse.enums.BudgetStatus.SUBMITTED ? budgets.authorityOf(b) : null);
        model.addAttribute("submitBy", budgets.submitBy(b.getBudgetYear()));
        model.addAttribute("approveBy", budgets.approveBy(b.getBudgetYear()));
        model.addAttribute("history", dataChangeService.historyWithChildren("Budget", id.toString(), "BudgetLine", "budget",
                Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "budgets/view";
    }

    /** A department's lines (centre given) or the company's (none): the form while it may be changed, else read only. */
    @GetMapping("/{id}/lines")
    @PreAuthorize(VIEW)
    public String lines(@PathVariable UUID id, @RequestParam(required = false) UUID centre, Model model) {
        Budget b = budgets.findDetailed(id);
        return linesPage(model, b, centre, budgets.linesForm(id, centre));
    }

    @PostMapping("/{id}/lines")
    @PreAuthorize(LINES)
    public String saveLines(@PathVariable UUID id, @RequestParam(required = false) UUID centre, @ModelAttribute("linesDto") BudgetLinesDto dto,
                            BindingResult result, Model model, RedirectAttributes redirect) {
        Budget b = budgets.findDetailed(id);
        dto.getLines().removeIf(BudgetLinesDto.Line::isBlank);
        try {
            budgets.saveLines(id, centre, dto);
            String where = centre == null ? "the company" : centreCode(id, centre);
            activityLogService.record(MODULE, "UPDATE_BUDGET_LINES", "Saved " + dto.getLines().size() + " budget line(s) of " + where + " on " + b.getNumber(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("budget.lines.saved", b.getNumber()));
            return "redirect:/budgets/" + id + "/lines" + (centre == null ? "" : "?centre=" + centre);
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_BUDGET_LINES", "Failed to save budget lines on " + b.getNumber() + ": " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField().replaceAll("\\.months$", ".annual"), e.getMessageKey(), e.getArgs(), error);
                model.addAttribute("formErrors", result.getFieldErrors());
            } else {
                model.addAttribute("flashError", error);
            }
            return linesPage(model, b, centre, dto);
        }
    }

    @PostMapping("/{id}/departments/{centre}/submit")
    @PreAuthorize(LINES)
    public String submitDepartment(@PathVariable UUID id, @PathVariable UUID centre, RedirectAttributes redirect) {
        try {
            BudgetDepartment d = budgets.submitDepartment(id, centre);
            activityLogService.record(MODULE, "SUBMIT_BUDGET_DEPARTMENT", "Submitted the budget of " + d.getCostCentre().getCode() + " on "
                    + budgets.findDetailed(id).getNumber(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("budget.department.submitted", d.getCostCentre().getCode()));
        } catch (BusinessException e) {
            failed(redirect, "SUBMIT_BUDGET_DEPARTMENT", "submit a department's budget", id, e);
        }
        return "redirect:/budgets/" + id + "/lines?centre=" + centre;
    }

    @PostMapping("/{id}/departments/{centre}/return")
    @PreAuthorize(PREPARE)
    public String returnDepartment(@PathVariable UUID id, @PathVariable UUID centre, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        try {
            BudgetDepartment d = AuditContext.withReason(clean(reason), () -> budgets.returnDepartment(id, centre, reason));
            activityLogService.record(MODULE, "RETURN_BUDGET_DEPARTMENT", "Sent the budget of " + d.getCostCentre().getCode() + " back: " + d.getReturnedNote(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("budget.department.returned", d.getCostCentre().getCode()));
        } catch (BusinessException e) {
            failed(redirect, "RETURN_BUDGET_DEPARTMENT", "send a department's budget back", id, e);
        }
        return "redirect:/budgets/" + id + "/lines?centre=" + centre;
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(PREPARE)
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        return step(id, redirect, "SUBMIT_BUDGET", "Submitted", "budget.submitted", () -> budgets.submit(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVE)
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        return step(id, redirect, "APPROVE_BUDGET", "Approved", "budget.approved", () -> budgets.approve(id, note));
    }

    @PostMapping("/{id}/send-back")
    @PreAuthorize(APPROVE)
    public String sendBack(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return step(id, redirect, "REJECT_BUDGET", "Sent back", "budget.sentBack", () -> AuditContext.withReason(clean(reason), () -> budgets.sendBack(id, reason)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(PREPARE)
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return step(id, redirect, "CANCEL_BUDGET", "Cancelled", "budget.cancelled", () -> AuditContext.withReason(clean(reason), () -> budgets.cancel(id, reason)));
    }

    // ---------------------------------------------------------------- revisions

    @GetMapping("/lines/{lineId}/revise")
    @PreAuthorize(REVISE)
    public String revise(@PathVariable UUID lineId, Model model) {
        BudgetLine line = budgets.line(lineId);
        BudgetRevisionDto dto = new BudgetRevisionDto();
        dto.setMonths(new ArrayList<>(Arrays.stream(line.getMonths()).map(m -> m.setScale(0, RoundingMode.HALF_UP)).toList()));
        return reviseForm(model, line, dto);
    }

    @PostMapping("/lines/{lineId}/revise")
    @PreAuthorize(REVISE)
    public String revise(@PathVariable UUID lineId, @Valid @ModelAttribute("revisionDto") BudgetRevisionDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        BudgetLine line = budgets.line(lineId);
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return reviseForm(model, line, dto);
        }
        try {
            BudgetRevision r = budgets.requestRevision(lineId, dto.getMonths(), dto.getReason());
            activityLogService.record(MODULE, "CREATE_BUDGET_REVISION", "Asked for budget revision " + r.getNumber() + ": " + num.money(r.getOldTotal())
                    + " to " + num.money(r.getNewTotal()) + " RWF (" + r.getChangePercent().toPlainString() + " %): " + r.getReason(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("budget.revision.saved", r.getNumber()));
            return "redirect:/budgets/revisions/" + r.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CREATE_BUDGET_REVISION", "Failed to ask for a budget revision: " + error, ActivityStatus.FAILED);
            model.addAttribute("flashError", error);
            return reviseForm(model, line, dto);
        }
    }

    @GetMapping("/revisions/{id}")
    @PreAuthorize(VIEW)
    public String revision(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        BudgetRevision r = budgets.revision(id);
        model.addAttribute("revision", r);
        model.addAttribute("mine", budgets.isRequester(r));
        model.addAttribute("authority", r.isWaiting() ? budgets.authorityOf(r) : null);
        model.addAttribute("monthStarts", com.ntaganira.heritier.iWarehouse.service.Budgets.monthStarts(r.getBudget().getBudgetYear()));
        model.addAttribute("history", dataChangeService.history("BudgetRevision", id.toString(), Paging.page(page), Paging.SIZE));
        return "budgets/revision";
    }

    @PostMapping("/revisions/{id}/approve")
    @PreAuthorize(DECIDE_REVISION)
    public String approveRevision(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        return revisionStep(id, redirect, "APPROVE_BUDGET_REVISION", "Approved", "budget.revision.approved", () -> budgets.approveRevision(id, note));
    }

    @PostMapping("/revisions/{id}/reject")
    @PreAuthorize(DECIDE_REVISION)
    public String rejectRevision(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return revisionStep(id, redirect, "REJECT_BUDGET_REVISION", "Rejected", "budget.revision.rejected",
                () -> AuditContext.withReason(clean(reason), () -> budgets.rejectRevision(id, reason)));
    }

    @PostMapping("/revisions/{id}/cancel")
    @PreAuthorize(REVISE)
    public String cancelRevision(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return revisionStep(id, redirect, "CANCEL_BUDGET_REVISION", "Cancelled", "budget.revision.cancelled",
                () -> AuditContext.withReason(clean(reason), () -> budgets.cancelRevision(id, reason)));
    }

    // ---------------------------------------------------------------- helpers

    private String saveHeader(UUID id, BudgetDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        Budget existing = id == null ? null : budgets.findDetailed(id);
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto, existing);
        }
        try {
            Budget b = id == null ? budgets.create(dto) : budgets.update(id, dto);
            activityLogService.record(MODULE, id == null ? "CREATE_BUDGET" : "UPDATE_BUDGET", (id == null ? "Started" : "Changed") + " budget " + b.getNumber()
                    + " for " + b.getBudgetYear() + ": " + b.getTitle(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("budget.saved", b.getNumber()));
            return "redirect:/budgets/" + b.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, id == null ? "CREATE_BUDGET" : "UPDATE_BUDGET", "Failed to save a budget: " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
                model.addAttribute("formErrors", result.getFieldErrors());
            } else {
                model.addAttribute("flashError", error);
            }
            return form(model, dto, existing);
        }
    }

    private String form(Model model, BudgetDto dto, Budget budget) {
        model.addAttribute("budgetDto", dto);
        model.addAttribute("budget", budget);
        return "budgets/form";
    }

    private String linesPage(Model model, Budget b, UUID centre, BudgetLinesDto dto) {
        BudgetDepartment dept = centre == null ? null : budgets.departments(b.getId()).stream()
                .filter(d -> d.getCostCentre().getId().equals(centre)).findFirst().orElse(null);
        model.addAttribute("budget", b);
        model.addAttribute("centre", centre);
        model.addAttribute("department", dept);
        model.addAttribute("editable", budgets.mayEdit(b, dept));
        model.addAttribute("head", dept != null && budgets.isHead(dept.getCostCentre()));
        model.addAttribute("linesDto", dto);
        model.addAttribute("savedLines", budgets.linesOf(b.getId(), centre));
        model.addAttribute("accounts", budgets.accounts());
        model.addAttribute("submitBy", budgets.submitBy(b.getBudgetYear()));
        model.addAttribute("monthStarts", com.ntaganira.heritier.iWarehouse.service.Budgets.monthStarts(b.getBudgetYear()));
        return "budgets/lines";
    }

    private String reviseForm(Model model, BudgetLine line, BudgetRevisionDto dto) {
        model.addAttribute("line", line);
        model.addAttribute("revisionDto", dto);
        model.addAttribute("monthStarts", com.ntaganira.heritier.iWarehouse.service.Budgets.monthStarts(line.getBudget().getBudgetYear()));
        return "budgets/revise";
    }

    private String step(UUID id, RedirectAttributes redirect, String action, String verb, String successKey, Supplier<Budget> run) {
        try {
            Budget b = run.get();
            String note = b.getDecisionNote() != null && !"SUBMIT_BUDGET".equals(action) ? ": " + b.getDecisionNote()
                    : b.getReturnedNote() != null && "REJECT_BUDGET".equals(action) ? ": " + b.getReturnedNote() : "";
            activityLogService.record(MODULE, action, verb + " budget " + b.getNumber() + " for " + b.getBudgetYear() + note, ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(successKey, b.getNumber()));
        } catch (BusinessException e) {
            failed(redirect, action, verb.toLowerCase() + " a budget", id, e);
        }
        return "redirect:/budgets/" + id;
    }

    private String revisionStep(UUID id, RedirectAttributes redirect, String action, String verb, String successKey, Supplier<BudgetRevision> run) {
        try {
            BudgetRevision r = run.get();
            activityLogService.record(MODULE, action, verb + " budget revision " + r.getNumber() + " (" + num.money(r.getOldTotal()) + " to "
                    + num.money(r.getNewTotal()) + " RWF)" + (r.getDecisionNote() == null ? "" : ": " + r.getDecisionNote()), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(successKey, r.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed: " + verb.toLowerCase() + " a budget revision: " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/budgets/revisions/" + id;
    }

    private void failed(RedirectAttributes redirect, String action, String what, UUID id, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        String number;
        try {
            number = budgets.findDetailed(id).getNumber();
        } catch (RuntimeException ex) {
            number = id.toString();
        }
        activityLogService.record(MODULE, action, "Failed to " + what + " on " + number + ": " + error, ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }

    private String centreCode(UUID budgetId, UUID centre) {
        return budgets.departments(budgetId).stream().filter(d -> d.getCostCentre().getId().equals(centre)).map(d -> d.getCostCentre().getCode())
                .findFirst().orElse(centre.toString());
    }

    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
