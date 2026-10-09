package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.SupplierPayment;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.service.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
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
 *               it settled, the realised FX gain or loss, its journal and History.
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

    public SupplierPaymentController(SupplierAccountService accountService, JournalService journalService, DataChangeService dataChangeService,
                                     ActivityLogService activityLogService, Messages messages, NumberFormats num) {
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

    /** How a supplier is paid: by bank transfer, cash from the vault or mobile money. */
    static List<PaymentMethod> methods() {
        return List.of(PaymentMethod.BANK_TRANSFER, PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY);
    }
}
