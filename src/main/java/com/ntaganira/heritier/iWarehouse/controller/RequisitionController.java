package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.RequisitionDto;
import com.ntaganira.heritier.iWarehouse.entity.PurchaseRequisition;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseNature;
import com.ntaganira.heritier.iWarehouse.enums.RequisitionStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.AttachmentService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.RequisitionService;
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
import java.util.function.Supplier;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : RequisitionController.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Purchase requisitions (CMP 06): the list, the form of a draft (items, quotes, the one chosen), the
 *               requisition with its quote documents and history, and its steps: submit, approve, reject, cancel.
 *               PAGE_REQUISITIONS with PERM_VIEW_ / CREATE_ / APPROVE_REQUISITION.
 * </pre>
 */
@Controller
@RequestMapping("/requisitions")
public class RequisitionController {

    static final String MODULE = "Purchasing";
    private static final String BASE = "/requisitions";
    private static final String VIEW = "hasAuthority('PAGE_REQUISITIONS') and hasAuthority('PERM_VIEW_REQUISITION')";
    private static final String CREATE = "hasAuthority('PAGE_REQUISITIONS') and hasAuthority('PERM_CREATE_REQUISITION')";
    private static final String APPROVE = "hasAuthority('PAGE_REQUISITIONS') and hasAuthority('PERM_APPROVE_REQUISITION')";

    private final RequisitionService service;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final SpringValidatorAdapter validator;
    private final Messages messages;
    private final NumberFormats num;

    public RequisitionController(RequisitionService service, AttachmentService attachmentService, DataChangeService dataChangeService,
                                 ActivityLogService activityLogService, SpringValidatorAdapter validator, Messages messages, NumberFormats num) {
        this.service = service;
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
        model.addAttribute("requisitions", service.findPage(search, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", RequisitionStatus.values());
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("waiting", service.waitingCount());
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status));
        return "requisitions/list";
    }

    @GetMapping("/new")
    @PreAuthorize(CREATE)
    public String create(Model model) {
        RequisitionDto dto = new RequisitionDto();
        dto.getLines().add(new RequisitionDto.Line());
        dto.getQuotes().add(new RequisitionDto.Quote());
        return form(model, dto, null);
    }

    @PostMapping("/new")
    @PreAuthorize(CREATE)
    public String create(@ModelAttribute("requisitionDto") RequisitionDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        return save(null, dto, result, model, redirect);
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize(CREATE)
    public String edit(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        PurchaseRequisition r = service.findDetailed(id);
        if (!r.isDraft()) {
            redirect.addFlashAttribute("flashError", messages.get("requisition.notDraft", r.getNumber()));
            return "redirect:" + BASE + "/" + id;
        }
        return form(model, service.formOf(r), r);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize(CREATE)
    public String edit(@PathVariable UUID id, @ModelAttribute("requisitionDto") RequisitionDto dto, BindingResult result, Model model,
                       RedirectAttributes redirect) {
        return save(id, dto, result, model, redirect);
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public String view(@PathVariable UUID id, @RequestParam(required = false) String tab, @RequestParam(defaultValue = "0") int page, Model model) {
        PurchaseRequisition r = service.findDetailed(id);
        String open = "history".equals(tab) ? tab : "documents";
        model.addAttribute("req", r);
        model.addAttribute("mine", service.isRequester(r));
        model.addAttribute("sourcing", service.sourcingOf(r));
        model.addAttribute("tiers", service.tiers());
        model.addAttribute("documentCount", attachmentService.count(AttachmentOwner.PURCHASE_REQUISITION, id));
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.PURCHASE_REQUISITION, id, Paging.pageOf("documents", open, page),
                Paging.SIZE));
        model.addAttribute("history", dataChangeService.historyWithChildren("PurchaseRequisition", id.toString(),
                List.of("PurchaseRequisitionLine", "RequisitionQuote"), "requisition", Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "requisitions/view";
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(CREATE)
    public String submit(@PathVariable UUID id, RedirectAttributes redirect) {
        return step(id, redirect, "SUBMIT_REQUISITION", "Submitted", "requisition.submitted", () -> service.submit(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVE)
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        return step(id, redirect, "APPROVE_REQUISITION", "Approved", "requisition.approved", () -> service.approve(id, note));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(APPROVE)
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return step(id, redirect, "REJECT_REQUISITION", "Rejected", "requisition.rejected",
                () -> AuditContext.withReason(clean(reason), () -> service.reject(id, reason)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(CREATE)
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return step(id, redirect, "CANCEL_REQUISITION", "Cancelled", "requisition.cancelled",
                () -> AuditContext.withReason(clean(reason), () -> service.cancel(id, reason)));
    }

    // ---------------------------------------------------------------- helpers

    private String save(UUID id, RequisitionDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        dto.getLines().removeIf(RequisitionDto.Line::isBlank);
        dto.getQuotes().removeIf(RequisitionDto.Quote::isBlank);
        validator.validate(dto, result);
        PurchaseRequisition existing = id == null ? null : service.findDetailed(id);
        if (result.hasErrors()) {
            model.addAttribute("formErrors", result.getFieldErrors());
            return form(model, dto, existing);
        }
        try {
            PurchaseRequisition r = service.save(id, dto);
            activityLogService.record(MODULE, id == null ? "CREATE_REQUISITION" : "UPDATE_REQUISITION", (id == null ? "Raised" : "Changed")
                    + " requisition " + r.getNumber() + " for " + r.getCostCentre().getCode() + ", estimated " + num.money(r.getEstimatedTotal())
                    + " RWF (" + r.getQuotes().size() + " quote(s)): " + r.getTitle(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("requisition.saved", r.getNumber()));
            return "redirect:" + BASE + "/" + r.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, id == null ? "CREATE_REQUISITION" : "UPDATE_REQUISITION", "Failed to save a requisition: " + error,
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

    private String form(Model model, RequisitionDto dto, PurchaseRequisition r) {
        model.addAttribute("requisitionDto", dto);
        model.addAttribute("req", r);
        model.addAttribute("costCentres", service.costCentres());
        model.addAttribute("suppliers", service.suppliers());
        model.addAttribute("natures", ExpenseNature.values());
        model.addAttribute("tiers", service.tiers());
        model.addAttribute("today", service.today());
        return "requisitions/form";
    }

    private String step(UUID id, RedirectAttributes redirect, String action, String verb, String successKey, Supplier<PurchaseRequisition> run) {
        try {
            PurchaseRequisition r = run.get();
            String note = r.getDecisionNote() != null && !"SUBMIT_REQUISITION".equals(action) ? ": " + r.getDecisionNote() : "";
            activityLogService.record(MODULE, action, verb + " requisition " + r.getNumber() + " (" + r.getCostCentre().getCode() + ", "
                    + num.money(r.getEstimatedTotal()) + " RWF)" + note, ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(successKey, r.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed: " + verb.toLowerCase() + " requisition " + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:" + BASE + "/" + id;
    }

    private String numberOf(UUID id) {
        try {
            return service.findDetailed(id).getNumber();
        } catch (RuntimeException e) {
            return id.toString();
        }
    }

    private static String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
