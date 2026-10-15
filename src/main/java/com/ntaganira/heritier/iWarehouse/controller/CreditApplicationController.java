package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.CreditApplicationDto;
import com.ntaganira.heritier.iWarehouse.entity.CreditApplication;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.CreditApplicationStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : CreditApplicationController.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Credit applications (F-02 C-D): the list with the customers due for a credit review, the form (from the
 *               customer's page), an application with its assessment, review and approval within the Delegation of
 *               Authority, rejection and withdrawal (reasons kept), its documents and History. PAGE_CREDIT_APPLICATIONS;
 *               ask PERM_REQUEST_CREDIT_TERMS; assess PERM_ASSESS_CREDIT; review PERM_REVIEW_CREDIT_APPLICATION; approve
 *               PERM_APPROVE_CREDIT_APPLICATION.
 * </pre>
 */
@Controller
@RequestMapping("/credit-applications")
public class CreditApplicationController {

    private static final int REASON_MAX = 255;

    private final CreditApplicationService applicationService;
    private final CustomerService customerService;
    private final AttachmentService attachmentService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public CreditApplicationController(CreditApplicationService applicationService, CustomerService customerService,
                                       AttachmentService attachmentService, DataChangeService dataChangeService,
                                       ActivityLogService activityLogService, Messages messages, NumberFormats num) {
        this.applicationService = applicationService;
        this.customerService = customerService;
        this.attachmentService = attachmentService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_CREDIT_APPLICATIONS')")
    public String list(@RequestParam(required = false) String status, @RequestParam(required = false) String search,
                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "0") int rpage, Model model) {
        model.addAttribute("applications", applicationService.findPage(status, search, Paging.page(page), Paging.SIZE));
        model.addAttribute("reviews", Paging.of(applicationService.reviewsDue(), rpage));
        model.addAttribute("statuses", CreditApplicationStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("search", search);
        model.addAttribute("paginationQuery", QueryString.of("status", status, "search", search));
        model.addAttribute("reviewMonths", applicationService.reviewMonths());
        model.addAttribute("reviewOverdueDays", applicationService.reviewOverdueDays());
        return "credit-applications/list";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_APPLICATIONS') and hasAuthority('PERM_REQUEST_CREDIT_TERMS')")
    public String form(@RequestParam UUID customer, Model model) {
        Customer c = customerService.findById(customer);
        CreditApplicationDto dto = new CreditApplicationDto();
        dto.setCreditLimit(c.getCreditLimit().stripTrailingZeros());
        dto.setPaymentTermsDays(c.getPaymentTermsDays());
        dto.setPriceListId(c.getPriceList() == null ? null : c.getPriceList().getId());
        return form(model, c, dto);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_APPLICATIONS') and hasAuthority('PERM_REQUEST_CREDIT_TERMS')")
    public String request(@RequestParam UUID customer, @Valid @ModelAttribute("applicationDto") CreditApplicationDto dto,
                          BindingResult result, Model model, RedirectAttributes redirect) {
        Customer c = customerService.findById(customer);
        if (result.hasErrors()) {
            return form(model, c, dto);
        }
        try {
            CreditApplication a = applicationService.request(customer, new CreditApplicationService.Form(dto.getCreditLimit(),
                    dto.getPaymentTermsDays(), dto.getPriceListId(), dto.getReason()));
            activityLogService.record(CustomerController.MODULE, "CREATE_CREDIT_APPLICATION", "Asked for credit terms for " + c.getName()
                    + " (" + a.getNumber() + "): " + num.money(a.getCreditLimit()) + " RWF, " + a.getPaymentTermsDays() + " days, was "
                    + num.money(a.getPreviousLimit()) + " RWF, " + a.getPreviousTermsDays() + " days: " + a.getReason(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("creditApp.created", a.getNumber()));
            return "redirect:/credit-applications/" + a.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(CustomerController.MODULE, "CREATE_CREDIT_APPLICATION", "Failed to ask for credit terms for "
                    + c.getName() + ": " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
            } else {
                model.addAttribute("flashError", error);
            }
            return form(model, c, dto);
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_APPLICATIONS')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "documents") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        CreditApplication a = applicationService.findDetailed(id);
        String open = "history".equals(tab) ? tab : "documents";
        model.addAttribute("app", a);
        model.addAttribute("mine", CreditApplicationService.isRequester(a));
        model.addAttribute("assessor", CreditApplicationService.isAssessor(a));
        model.addAttribute("reviewer", CreditApplicationService.isReviewer(a));
        model.addAttribute("authority", a.getStatus() == CreditApplicationStatus.PENDING_APPROVAL ? applicationService.authorityOf(a) : null);
        model.addAttribute("documents", attachmentService.page(AttachmentOwner.CREDIT_APPLICATION, id, Paging.pageOf("documents", open, page), Paging.SIZE));
        model.addAttribute("history", dataChangeService.history("CreditApplication", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "credit-applications/view";
    }

    @PostMapping("/{id}/assess")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_APPLICATIONS') and hasAuthority('PERM_ASSESS_CREDIT')")
    public String assess(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            CreditApplication a = applicationService.assess(id, note);
            activityLogService.record(CustomerController.MODULE, "REVIEW_CREDIT_APPLICATION", "Assessed credit application " + a.getNumber()
                    + " of " + a.getCustomer().getName() + ": " + a.getAssessmentNote(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("creditApp.assessed", a.getNumber()));
        } catch (BusinessException e) {
            failed(id, "REVIEW_CREDIT_APPLICATION", "assess", e, redirect);
        }
        return "redirect:/credit-applications/" + id;
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_APPLICATIONS') and hasAuthority('PERM_REVIEW_CREDIT_APPLICATION')")
    public String review(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            CreditApplication a = applicationService.review(id, note);
            activityLogService.record(CustomerController.MODULE, "REVIEW_CREDIT_APPLICATION", "Reviewed credit application " + a.getNumber()
                    + " of " + a.getCustomer().getName() + (StringUtils.hasText(note) ? ": " + note.trim() : ""), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("creditApp.reviewed", a.getNumber()));
        } catch (BusinessException e) {
            failed(id, "REVIEW_CREDIT_APPLICATION", "review", e, redirect);
        }
        return "redirect:/credit-applications/" + id;
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_APPLICATIONS') and hasAuthority('PERM_APPROVE_CREDIT_APPLICATION')")
    public String approve(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            CreditApplication a = applicationService.approve(id, note);
            activityLogService.record(CustomerController.MODULE, "APPROVE_CREDIT_APPLICATION", "Approved credit application " + a.getNumber()
                    + " of " + a.getRequestedBy() + ": " + a.getCustomer().getName() + " now has " + num.money(a.getCreditLimit()) + " RWF, "
                    + a.getPaymentTermsDays() + " days" + (a.getDecisionNote() != null ? " (" + a.getDecisionNote() + ")" : ""), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("creditApp.approved", a.getNumber(), a.getCustomer().getName()));
        } catch (BusinessException e) {
            failed(id, "APPROVE_CREDIT_APPLICATION", "approve", e, redirect);
        }
        return "redirect:/credit-applications/" + id;
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_APPLICATIONS') and (hasAuthority('PERM_ASSESS_CREDIT') or hasAuthority('PERM_REVIEW_CREDIT_APPLICATION') or hasAuthority('PERM_APPROVE_CREDIT_APPLICATION'))")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return withReason(id, reason, redirect, true);
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('PAGE_CREDIT_APPLICATIONS') and hasAuthority('PERM_REQUEST_CREDIT_TERMS')")
    public String withdraw(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return withReason(id, reason, redirect, false);
    }

    private String withReason(UUID id, String reason, RedirectAttributes redirect, boolean reject) {
        String action = reject ? "REJECT_CREDIT_APPLICATION" : "CANCEL_CREDIT_APPLICATION";
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/credit-applications/" + id;
        }
        try {
            CreditApplication a = AuditContext.withReason(reason.trim(),
                    () -> reject ? applicationService.reject(id, reason) : applicationService.withdraw(id, reason));
            activityLogService.record(CustomerController.MODULE, action, (reject ? "Rejected" : "Withdrew") + " credit application "
                    + a.getNumber() + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(reject ? "creditApp.rejectedMsg" : "creditApp.withdrawnMsg", a.getNumber()));
        } catch (BusinessException e) {
            failed(id, action, reject ? "reject" : "withdraw", e, redirect);
        }
        return "redirect:/credit-applications/" + id;
    }

    private String form(Model model, Customer customer, CreditApplicationDto dto) {
        model.addAttribute("customer", customer);
        model.addAttribute("applicationDto", dto);
        model.addAttribute("priceLists", applicationService.priceLists());
        model.addAttribute("waiting", applicationService.waitingOf(customer.getId()).orElse(null));
        return "credit-applications/form";
    }

    private void failed(UUID id, String action, String verb, BusinessException e, RedirectAttributes redirect) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        String number;
        try {
            number = applicationService.findDetailed(id).getNumber();
        } catch (RuntimeException ex) {
            number = id.toString();
        }
        activityLogService.record(CustomerController.MODULE, action, "Failed to " + verb + " credit application " + number + ": " + error,
                ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }
}
