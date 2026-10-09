package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.entity.CustomerPayment;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
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
 * - File      : CustomerPaymentController.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Payments on customer accounts (ACC-09): taking one from the customer's page, the list, a payment with
 *               its journal and History, and its 80 mm receipt to print.
 * </pre>
 */
@Controller
@RequestMapping("/customer-payments")
public class CustomerPaymentController {

    private final CustomerAccountService accountService;
    private final TillService tillService;
    private final JournalService journalService;
    private final DataChangeService dataChangeService;
    private final SettingService settingService;
    private final ActivityLogService activityLogService;
    private final Messages messages;
    private final NumberFormats num;

    public CustomerPaymentController(CustomerAccountService accountService, TillService tillService, JournalService journalService,
                                     DataChangeService dataChangeService, SettingService settingService,
                                     ActivityLogService activityLogService, Messages messages, NumberFormats num) {
        this.accountService = accountService;
        this.tillService = tillService;
        this.journalService = journalService;
        this.dataChangeService = dataChangeService;
        this.settingService = settingService;
        this.activityLogService = activityLogService;
        this.messages = messages;
        this.num = num;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_CUSTOMER_PAYMENTS') and hasAuthority('PERM_VIEW_CUSTOMER_ACCOUNT')")
    public String list(@RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("payments", accountService.findPage(search, Paging.page(page), Paging.SIZE));
        model.addAttribute("search", search);
        model.addAttribute("paginationQuery", QueryString.of("search", search));
        return "customer-payments/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_CUSTOMER_PAYMENTS') and hasAuthority('PERM_VIEW_CUSTOMER_ACCOUNT')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, Model model) {
        CustomerPayment payment = accountService.findDetailed(id);
        model.addAttribute("payment", payment);
        model.addAttribute("till", payment.getTillSessionId() == null ? null : tillService.findById(payment.getTillSessionId()));
        model.addAttribute("owed", accountService.owed(payment.getCustomer().getId()));
        model.addAttribute("journals", journalService.forSource(id, JournalSource.CUSTOMER_PAYMENT));
        model.addAttribute("history", dataChangeService.history("CustomerPayment", id.toString(), Paging.page(page), Paging.SIZE));
        return "customer-payments/view";
    }

    /** The receipt for the 80 mm receipt printer. */
    @GetMapping("/{id}/receipt")
    @PreAuthorize("hasAuthority('PAGE_CUSTOMER_PAYMENTS') and hasAuthority('PERM_VIEW_CUSTOMER_ACCOUNT')")
    public String receipt(@PathVariable UUID id, Model model) {
        CustomerPayment payment = accountService.findDetailed(id);
        model.addAttribute("payment", payment);
        model.addAttribute("owed", accountService.owed(payment.getCustomer().getId()));
        model.addAttribute("companyName", settingService.get(SettingKey.COMPANY_NAME));
        model.addAttribute("companyTin", settingService.get(SettingKey.COMPANY_TIN));
        model.addAttribute("companyAddress", settingService.get(SettingKey.COMPANY_ADDRESS));
        model.addAttribute("companyPhone", settingService.get(SettingKey.COMPANY_PHONE));
        activityLogService.record(PosController.MODULE, "PRINT_CUSTOMER_PAYMENT", "Opened payment " + payment.getNumber() + " for printing",
                ActivityStatus.SUCCESS);
        return "customer-payments/receipt";
    }

    /** Takes a payment on a customer's account, from the customer's page. */
    @PostMapping
    @PreAuthorize("hasAuthority('PAGE_CUSTOMERS') and hasAuthority('PERM_RECEIVE_CUSTOMER_PAYMENT')")
    public String receive(@RequestParam UUID customerId, @RequestParam(required = false) BigDecimal amount,
                          @RequestParam(required = false) PaymentMethod method, @RequestParam(required = false) String reference,
                          @RequestParam(required = false) BigDecimal cashTendered, @RequestParam(required = false) String notes,
                          RedirectAttributes redirect) {
        try {
            CustomerPayment payment = accountService.receive(customerId,
                    new CustomerAccountService.PaymentForm(amount, method, reference, cashTendered, notes));
            activityLogService.record(PosController.MODULE, "CREATE_CUSTOMER_PAYMENT", "Took payment " + payment.getNumber() + " from "
                    + payment.getCustomer().getName() + ": " + num.money(payment.getAmount()) + " RWF by " + payment.getMethod(), ActivityStatus.SUCCESS);
            String text = messages.get("customerPayment.taken", payment.getNumber(), num.money(payment.getAmount()));
            BigDecimal change = payment.getChange();
            if (change != null && change.signum() > 0) {
                text = text + ". " + messages.get("sale.giveChange", num.money(change));
            }
            redirect.addFlashAttribute("flashSuccess", text);
            return "redirect:/customer-payments/" + payment.getId();
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(PosController.MODULE, "CREATE_CUSTOMER_PAYMENT", "Failed to take a payment from customer " + customerId
                    + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
            Map<String, String> form = new HashMap<>();
            form.put("amount", amount == null ? "" : amount.toPlainString());
            form.put("method", method == null ? "" : method.name());
            form.put("reference", reference);
            form.put("cashTendered", cashTendered == null ? "" : cashTendered.toPlainString());
            form.put("notes", notes);
            redirect.addFlashAttribute("payForm", form);
            redirect.addFlashAttribute("payField", e.getField());
            return "redirect:/customers/" + customerId + "?tab=account";
        }
    }

    /** The ways a payment on account is taken: never "credit". */
    static List<PaymentMethod> methods() {
        return List.of(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY, PaymentMethod.CARD, PaymentMethod.BANK_TRANSFER);
    }
}
