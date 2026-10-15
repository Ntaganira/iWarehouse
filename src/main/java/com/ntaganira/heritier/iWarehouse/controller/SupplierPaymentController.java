package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.SupplierPayment;
import com.ntaganira.heritier.iWarehouse.entity.SupplierPaymentRequest;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.PaymentRequestStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : SupplierPaymentController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Payments to suppliers (ACC-08, ACC-09): paying from the supplier's page, the list, and a payment with what
 *               it settled, the realised FX gain or loss, its journal and History. With authorisation on (CMP 07) the
 *               supplier's page prepares a payment request, paid once another person (two above the Settings amount)
 *               authorises it within the Delegation of Authority; rejected or cancelled with a reason.
 * </pre>
 */
@Controller
@RequestMapping("/supplier-payments")
public class SupplierPaymentController {

    private final SupplierAccountService accountService;
    private final JournalService journalService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;
    private final DelegationOfAuthority authority;

    public SupplierPaymentController(SupplierAccountService accountService, JournalService journalService, DataChangeService dataChangeService,
                                     ActivityLogService activityLogService, Messages messages, NumberFormats num, DelegationOfAuthority authority) {
        this.authority = authority;
        this.accountService = accountService;
        this.journalService = journalService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_SUPPLIER_PAYMENTS') and hasAuthority('PERM_VIEW_SUPPLIER_ACCOUNT')")
    public String list(@RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("payments", accountService.findPayments(search, Paging.page(page), Paging.SIZE));
        model.addAttribute("waitingRequests", accountService.waitingRequests());
        model.addAttribute("search", search);
        model.addAttribute("paginationQuery", QueryString.of("search", search));
        return "supplier-payments/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIER_PAYMENTS') and hasAuthority('PERM_VIEW_SUPPLIER_ACCOUNT')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("payment", accountService.findPayment(id));
        model.addAttribute("journals", journalService.forSource(id, JournalSource.SUPPLIER_PAYMENT));
        model.addAttribute("history", dataChangeService.history("SupplierPayment", id.toString(), Paging.page(page), Paging.SIZE));
        return "supplier-payments/view";
    }

    /** Pays a supplier, from the supplier's page. */
    @PostMapping
    @PreAuthorize("hasAuthority('PAGE_SUPPLIERS') and hasAuthority('PERM_PAY_SUPPLIER')")
    public String pay(@RequestParam UUID supplierId, @RequestParam(required = false) String currencyCode,
                      @RequestParam(required = false) BigDecimal amount, @RequestParam(required = false) PaymentMethod method,
                      @RequestParam(required = false) String reference, @RequestParam(required = false) String notes,
                      RedirectAttributes redirect) {
        try {
            if (accountService.authorisationRequired()) {
                SupplierPaymentRequest r = accountService.prepare(supplierId,
                        new SupplierAccountService.PaymentForm(currencyCode, amount, method, reference, notes));
                activityLogService.record(SupplierInvoiceController.MODULE, "CREATE_SUPPLIER_PAYMENT_REQUEST", "Prepared a payment to "
                        + r.getSupplier().getName() + " of " + num.amount(r.getAmount(), 2) + " " + r.getCurrencyCode() + " (" + r.getNumber()
                        + ", about " + num.money(r.getBaseEstimate()) + " RWF, " + r.getAuthorisations() + " authorisation(s))", ActivityStatus.SUCCESS);
                redirect.addFlashAttribute("flashSuccess", messages.get("supplierPayment.request.prepared", r.getNumber()));
                return "redirect:/supplier-payments/requests/" + r.getId();
            }
            SupplierPayment payment = accountService.pay(supplierId,
                    new SupplierAccountService.PaymentForm(currencyCode, amount, method, reference, notes));
            activityLogService.record(SupplierInvoiceController.MODULE, "CREATE_SUPPLIER_PAYMENT", "Paid " + payment.getSupplier().getName()
                    + " " + num.amount(payment.getAmount(), 2) + " " + payment.getCurrencyCode() + " (" + payment.getNumber() + ", "
                    + num.money(payment.getBaseAmount()) + " RWF, FX " + num.money(payment.getFxGainLoss()) + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("supplierPayment.paid", payment.getNumber(), num.amount(payment.getAmount(), 2),
                    payment.getCurrencyCode()));
            return "redirect:/supplier-payments/" + payment.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(SupplierInvoiceController.MODULE, "CREATE_SUPPLIER_PAYMENT", "Failed to pay supplier " + supplierId + ": "
                    + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
            Map<String, String> form = new HashMap<>();
            form.put("currencyCode", currencyCode);
            form.put("amount", amount == null ? "" : amount.toPlainString());
            form.put("method", method == null ? "" : method.name());
            form.put("reference", reference);
            form.put("notes", notes);
            redirect.addFlashAttribute("payForm", form);
            redirect.addFlashAttribute("payField", e.getField());
            return "redirect:/suppliers/" + supplierId + "?tab=account";
        }
    }

    // ---------------------------------------------------------------- prepared, then authorised (CMP 07)

    @GetMapping("/requests")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIER_PAYMENTS') and hasAuthority('PERM_VIEW_SUPPLIER_ACCOUNT')")
    public String requests(@RequestParam(required = false) String status, @RequestParam(required = false) String search,
                           @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("requests", accountService.findRequests(status, search, Paging.page(page), Paging.SIZE));
        model.addAttribute("statuses", PaymentRequestStatus.values());
        model.addAttribute("status", status);
        model.addAttribute("search", search);
        model.addAttribute("paginationQuery", QueryString.of("status", status, "search", search));
        return "supplier-payments/requests";
    }

    @GetMapping("/requests/{id}")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIER_PAYMENTS') and hasAuthority('PERM_VIEW_SUPPLIER_ACCOUNT')")
    public String request(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        SupplierPaymentRequest r = accountService.findRequest(id);
        model.addAttribute("prq", r);
        model.addAttribute("payment", r.getPaymentId() == null ? null : accountService.findPayment(r.getPaymentId()));
        model.addAttribute("mine", SupplierAccountService.isPreparer(r));
        model.addAttribute("first", SupplierAccountService.isFirstAuthoriser(r));
        model.addAttribute("hasOffice", authority.currentLevel().isPresent());
        // The last authorisation needs the office the amount needs, unless the first authoriser held it
        DelegationOfAuthority.Check check = r.isWaiting() ? accountService.authorityOf(r) : null;
        boolean last = r.getStatus() == PaymentRequestStatus.AUTHORISED || !r.isDual();
        boolean firstHolds = check != null && r.getFirstLevel() != null && r.getFirstLevel().atLeast(check.required());
        model.addAttribute("needed", check);
        model.addAttribute("authority", check != null && last && !firstHolds ? check : null);
        model.addAttribute("history", dataChangeService.history("SupplierPaymentRequest", id.toString(), Paging.page(page), Paging.SIZE));
        return "supplier-payments/request";
    }

    @PostMapping("/requests/{id}/authorise")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIER_PAYMENTS') and hasAuthority('PERM_AUTHORISE_SUPPLIER_PAYMENT')")
    public String authorise(@PathVariable UUID id, @RequestParam(required = false) String note, RedirectAttributes redirect) {
        try {
            SupplierPaymentRequest r = accountService.authorise(id, note);
            if (r.getStatus() == PaymentRequestStatus.PAID) {
                SupplierPayment payment = accountService.findPayment(r.getPaymentId());
                activityLogService.record(SupplierInvoiceController.MODULE, "APPROVE_SUPPLIER_PAYMENT", "Authorised payment request " + r.getNumber()
                        + " of " + r.getPreparedBy() + ": paid " + r.getSupplier().getName() + " " + num.amount(payment.getAmount(), 2) + " "
                        + payment.getCurrencyCode() + " (" + payment.getNumber() + ", " + num.money(payment.getBaseAmount()) + " RWF)", ActivityStatus.SUCCESS);
                redirect.addFlashAttribute("flashSuccess", messages.get("supplierPayment.paid", payment.getNumber(), num.amount(payment.getAmount(), 2),
                        payment.getCurrencyCode()));
                return "redirect:/supplier-payments/" + payment.getId();
            }
            activityLogService.record(SupplierInvoiceController.MODULE, "APPROVE_SUPPLIER_PAYMENT", "Gave the first authorisation of payment request "
                    + r.getNumber() + " of " + r.getPreparedBy() + " (" + r.getSupplier().getName() + ", " + num.amount(r.getAmount(), 2) + " "
                    + r.getCurrencyCode() + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("supplierPayment.request.firstDone", r.getNumber()));
        } catch (BusinessException e) {
            failed(id, "APPROVE_SUPPLIER_PAYMENT", "authorise", e, redirect);
        }
        return "redirect:/supplier-payments/requests/" + id;
    }

    @PostMapping("/requests/{id}/reject")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIER_PAYMENTS') and hasAuthority('PERM_AUTHORISE_SUPPLIER_PAYMENT')")
    public String reject(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return withReason(id, reason, redirect, true);
    }

    @PostMapping("/requests/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIER_PAYMENTS') and hasAuthority('PERM_PAY_SUPPLIER')")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        return withReason(id, reason, redirect, false);
    }

    private String withReason(UUID id, String reason, RedirectAttributes redirect, boolean reject) {
        String action = reject ? "REJECT_SUPPLIER_PAYMENT" : "CANCEL_SUPPLIER_PAYMENT";
        if (!StringUtils.hasText(reason) || reason.trim().length() > 255) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/supplier-payments/requests/" + id;
        }
        try {
            SupplierPaymentRequest r = AuditContext.withReason(reason.trim(),
                    () -> reject ? accountService.reject(id, reason) : accountService.cancel(id, reason));
            activityLogService.record(SupplierInvoiceController.MODULE, action, (reject ? "Rejected" : "Cancelled") + " payment request "
                    + r.getNumber() + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get(reject ? "supplierPayment.request.rejectedMsg"
                    : "supplierPayment.request.cancelledMsg", r.getNumber()));
        } catch (BusinessException e) {
            failed(id, action, reject ? "reject" : "cancel", e, redirect);
        }
        return "redirect:/supplier-payments/requests/" + id;
    }

    private void failed(UUID id, String action, String verb, BusinessException e, RedirectAttributes redirect) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        String number;
        try {
            number = accountService.findRequest(id).getNumber();
        } catch (RuntimeException ex) {
            number = id.toString();
        }
        activityLogService.record(SupplierInvoiceController.MODULE, action, "Failed to " + verb + " payment request " + number + ": " + error,
                ActivityStatus.FAILED);
        redirect.addFlashAttribute("flashError", error);
    }

    /** How a supplier is paid: by bank transfer, cash from the vault or mobile money. */
    static List<PaymentMethod> methods() {
        return List.of(PaymentMethod.BANK_TRANSFER, PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY);
    }
}
