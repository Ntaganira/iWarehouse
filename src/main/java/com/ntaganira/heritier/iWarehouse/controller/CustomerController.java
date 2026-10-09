package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.CustomerDto;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.CustomerType;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.Ageing;
import com.ntaganira.heritier.iWarehouse.service.CustomerAccountService;
import com.ntaganira.heritier.iWarehouse.service.CustomerService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.PriceListService;
import com.ntaganira.heritier.iWarehouse.service.TillService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : CustomerController.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Customers screens (MD-04): list with filters, detail with History, add, edit, activate and
 *               deactivate. PAGE_CUSTOMERS + PERM_VIEW_CUSTOMER; changes PERM_MANAGE_CUSTOMER; credit limit,
 *               payment terms and price list also need PERM_MANAGE_CUSTOMER_TERMS, and every change to
 *               them is spelled out in the activity log.
 * </pre>
 */
@Controller
@RequestMapping("/customers")
public class CustomerController {

    static final String MODULE = "Customers";
    static final String TERMS = "PERM_MANAGE_CUSTOMER_TERMS";

    private final CustomerService customerService;
    private final PriceListService priceListService;
    private final CustomerAccountService accountService;
    private final TillService tillService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public CustomerController(CustomerService customerService, PriceListService priceListService, CustomerAccountService accountService,
                              TillService tillService, DataChangeService dataChangeService, ActivityLogService activityLogService,
                              Messages messages) {
        this.customerService = customerService;
        this.priceListService = priceListService;
        this.accountService = accountService;
        this.tillService = tillService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_CUSTOMERS') and hasAuthority('PERM_VIEW_CUSTOMER')")
    public String list(@RequestParam(required = false) String search,
                       @RequestParam(required = false) CustomerType type,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        model.addAttribute("customers", customerService.findPage(search, type, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("types", CustomerType.values());
        model.addAttribute("defaultList", priceListService.defaultList());
        model.addAttribute("search", search);
        model.addAttribute("type", type);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "type", type, "status", status));
        return "customers/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_CUSTOMERS') and hasAuthority('PERM_VIEW_CUSTOMER')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "details") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        boolean seesAccount = AppUserPrincipal.currentHas("PERM_VIEW_CUSTOMER_ACCOUNT");
        String open = List.of("details", "history").contains(tab) || (seesAccount && "account".equals(tab)) ? tab : "details";
        Customer customer = customerService.findById(id);
        model.addAttribute("customer", customer);
        model.addAttribute("defaultList", priceListService.defaultList());
        // The account (ACC-09): its statement, balance and ageing, and taking a payment on it
        if (seesAccount) {
            CustomerAccountService.Account account = accountService.account(customer);
            model.addAttribute("account", account);
            model.addAttribute("statement", Paging.of(account.statement(), Paging.pageOf("account", open, page)));
            model.addAttribute("buckets", Ageing.Bucket.values());
            model.addAttribute("payMethods", CustomerPaymentController.methods());
            model.addAttribute("myTill", tillService.current().map(tillService::summary).orElse(null));
        }
        model.addAttribute("history", dataChangeService.history("Customer", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "customers/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_CUSTOMERS') and hasAuthority('PERM_MANAGE_CUSTOMER')")
    public String createForm(Model model) {
        return form(model, new CustomerDto());
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_CUSTOMERS') and hasAuthority('PERM_MANAGE_CUSTOMER')")
    public String create(@Valid @ModelAttribute("customerDto") CustomerDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Customer customer = customerService.create(dto, AppUserPrincipal.currentHas(TERMS));
            activityLogService.record(MODULE, "CREATE_CUSTOMER", "Added " + customer.getType().name().toLowerCase().replace('_', '-')
                    + " customer " + customer.getCode() + " " + customer.getName() + " (" + terms(customer) + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("customer.created", customer.getName(), customer.getCode()));
            return "redirect:/customers/" + customer.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_CUSTOMER", "Failed to add customer " + dto.getName() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_CUSTOMERS') and hasAuthority('PERM_MANAGE_CUSTOMER')")
    public String editForm(@PathVariable UUID id, Model model) {
        Customer c = customerService.findById(id);
        CustomerDto dto = new CustomerDto();
        dto.setId(id);
        dto.setType(c.getType());
        dto.setName(c.getName());
        dto.setTin(c.getTin());
        dto.setPhone(c.getPhone());
        dto.setEmail(c.getEmail());
        dto.setContactName(c.getContactName());
        dto.setAddress(c.getAddress());
        dto.setCreditLimit(c.getCreditLimit().stripTrailingZeros());
        dto.setPaymentTermsDays(c.getPaymentTermsDays());
        dto.setPriceListId(c.getPriceList() == null ? null : c.getPriceList().getId());
        dto.setNotes(c.getNotes());
        return form(model, dto);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_CUSTOMERS') and hasAuthority('PERM_MANAGE_CUSTOMER')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("customerDto") CustomerDto dto,
                         BindingResult result, Model model, RedirectAttributes redirect) {
        dto.setId(id);
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        String before = terms(customerService.findById(id));
        try {
            Customer customer = customerService.update(id, dto, AppUserPrincipal.currentHas(TERMS));
            String after = terms(customer);
            activityLogService.record(MODULE, "UPDATE_CUSTOMER", "Updated customer " + customer.getCode() + " " + customer.getName()
                    + (before.equals(after) ? "" : " (terms: " + before + " -> " + after + ")"), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("customer.updated", customer.getName()));
            return "redirect:/customers/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_CUSTOMER", "Failed to update customer " + codeOf(id) + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_CUSTOMERS') and hasAuthority('PERM_MANAGE_CUSTOMER')")
    public String disable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, false, redirect);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_CUSTOMERS') and hasAuthority('PERM_MANAGE_CUSTOMER')")
    public String enable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, true, redirect);
    }

    private String setEnabled(UUID id, boolean enabled, RedirectAttributes redirect) {
        String action = enabled ? "ENABLE_CUSTOMER" : "DISABLE_CUSTOMER";
        try {
            Customer customer = customerService.setEnabled(id, enabled);
            activityLogService.record(MODULE, action, (enabled ? "Activated" : "Deactivated") + " customer "
                    + customer.getCode() + " " + customer.getName(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess",
                    messages.get(enabled ? "customer.enabledMsg" : "customer.disabledMsg", customer.getName()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to change status of customer " + codeOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/customers/" + id;
    }

    /** "credit 2000000 RWF, 30 days, list CONTRACTOR" or "cash, default list" for the activity log. */
    private static String terms(Customer c) {
        List<String> parts = new ArrayList<>();
        BigDecimal credit = c.getCreditLimit();
        parts.add(credit.signum() == 0 ? "cash" : "credit " + credit.stripTrailingZeros().toPlainString() + " RWF");
        if (c.getPaymentTermsDays() > 0) {
            parts.add(c.getPaymentTermsDays() + " days");
        }
        parts.add(c.getPriceList() == null ? "default list" : "list " + c.getPriceList().getCode());
        return String.join(", ", parts);
    }

    private String codeOf(UUID id) {
        try {
            return customerService.findById(id).getCode();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }

    private String form(Model model, CustomerDto dto) {
        Customer current = dto.getId() == null ? null : customerService.findById(dto.getId());
        model.addAttribute("customerDto", dto);
        model.addAttribute("customerEntity", current);
        model.addAttribute("types", CustomerType.values());
        model.addAttribute("priceLists", customerService.priceListsFor(current));
        model.addAttribute("defaultList", priceListService.defaultList());
        model.addAttribute("canSetTerms", AppUserPrincipal.currentHas(TERMS));
        return "customers/form";
    }

    private String invalid(Model model, CustomerDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    /** A business rule refused the form: show it next to its field, or as a toast. */
    private String rejected(Model model, CustomerDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }
}
