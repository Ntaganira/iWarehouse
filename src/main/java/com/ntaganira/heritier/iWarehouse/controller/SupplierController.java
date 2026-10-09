package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Countries;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.SupplierDto;
import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.Incoterm;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.Ageing;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.SupplierAccountService;
import com.ntaganira.heritier.iWarehouse.service.SupplierService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : SupplierController.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Suppliers screens (MD-05): list with filters, detail with History, add, edit, activate
 *               and deactivate. PAGE_SUPPLIERS + PERM_VIEW_SUPPLIER; changes PERM_MANAGE_SUPPLIER.
 * </pre>
 */
@Controller
@RequestMapping("/suppliers")
public class SupplierController {

    static final String MODULE = "Suppliers";

    private final SupplierService supplierService;
    private final SupplierAccountService accountService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Countries countries;
    private final Messages messages;

    public SupplierController(SupplierService supplierService, SupplierAccountService accountService, DataChangeService dataChangeService,
                              ActivityLogService activityLogService, Countries countries, Messages messages) {
        this.supplierService = supplierService;
        this.accountService = accountService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.countries = countries;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_SUPPLIERS') and hasAuthority('PERM_VIEW_SUPPLIER')")
    public String list(@RequestParam(required = false) String search,
                       @RequestParam(required = false) String country,
                       @RequestParam(required = false) String currency,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        model.addAttribute("suppliers", supplierService.findPage(search, country, currency, status, Paging.page(page), Paging.SIZE));
        model.addAttribute("countryOptions", supplierService.countryCodes().stream()
                .map(code -> new Countries.Country(code, countries.name(code)))
                .sorted(Comparator.comparing(Countries.Country::name)).toList());
        model.addAttribute("currencyOptions", supplierService.currencyCodes());
        model.addAttribute("search", search);
        model.addAttribute("country", country);
        model.addAttribute("currency", currency);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "country", country, "currency", currency,
                "status", status));
        return "suppliers/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIERS') and hasAuthority('PERM_VIEW_SUPPLIER')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "details") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        boolean seesAccount = AppUserPrincipal.currentHas("PERM_VIEW_SUPPLIER_ACCOUNT");
        String open = List.of("details", "history").contains(tab) || (seesAccount && "account".equals(tab)) ? tab : "details";
        Supplier supplier = supplierService.findById(id);
        model.addAttribute("supplier", supplier);
        // The account (ACC-09): what is owed per currency, the ageing, receipts to invoice, the statement, paying
        if (seesAccount) {
            SupplierAccountService.Account account = accountService.account(supplier);
            model.addAttribute("account", account);
            model.addAttribute("statement", Paging.of(account.statement(), Paging.pageOf("account", open, page)));
            model.addAttribute("buckets", Ageing.Bucket.values());
            model.addAttribute("payCurrencies", account.open().stream().filter(o -> o.getAmount().signum() > 0).toList());
            model.addAttribute("payMethods", SupplierPaymentController.methods());
        }
        model.addAttribute("history", dataChangeService.history("Supplier", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "suppliers/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIERS') and hasAuthority('PERM_MANAGE_SUPPLIER')")
    public String createForm(Model model) {
        return form(model, new SupplierDto());
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIERS') and hasAuthority('PERM_MANAGE_SUPPLIER')")
    public String create(@Valid @ModelAttribute("supplierDto") SupplierDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Supplier supplier = supplierService.create(dto);
            activityLogService.record(MODULE, "CREATE_SUPPLIER", "Added supplier " + supplier.getCode() + " "
                    + supplier.getName() + " (" + supplier.getCountryCode() + ", " + supplier.getCurrencyCode()
                    + (supplier.getIncoterm() == null ? "" : ", " + supplier.getIncoterm()) + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("supplier.created", supplier.getName(), supplier.getCode()));
            return "redirect:/suppliers/" + supplier.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_SUPPLIER", "Failed to add supplier " + dto.getName() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIERS') and hasAuthority('PERM_MANAGE_SUPPLIER')")
    public String editForm(@PathVariable UUID id, Model model) {
        Supplier s = supplierService.findById(id);
        SupplierDto dto = new SupplierDto();
        dto.setId(id);
        dto.setName(s.getName());
        dto.setCountryCode(s.getCountryCode());
        dto.setCurrencyCode(s.getCurrencyCode());
        dto.setIncoterm(s.getIncoterm());
        dto.setTin(s.getTin());
        dto.setPaymentTermsDays(s.getPaymentTermsDays());
        dto.setContactName(s.getContactName());
        dto.setPhone(s.getPhone());
        dto.setEmail(s.getEmail());
        dto.setAddress(s.getAddress());
        dto.setNotes(s.getNotes());
        return form(model, dto);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIERS') and hasAuthority('PERM_MANAGE_SUPPLIER')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("supplierDto") SupplierDto dto,
                         BindingResult result, Model model, RedirectAttributes redirect) {
        dto.setId(id);
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Supplier supplier = supplierService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_SUPPLIER", "Updated supplier " + supplier.getCode() + " "
                    + supplier.getName(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("supplier.updated", supplier.getName()));
            return "redirect:/suppliers/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_SUPPLIER", "Failed to update supplier " + codeOf(id) + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIERS') and hasAuthority('PERM_MANAGE_SUPPLIER')")
    public String disable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, false, redirect);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_SUPPLIERS') and hasAuthority('PERM_MANAGE_SUPPLIER')")
    public String enable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, true, redirect);
    }

    private String setEnabled(UUID id, boolean enabled, RedirectAttributes redirect) {
        String action = enabled ? "ENABLE_SUPPLIER" : "DISABLE_SUPPLIER";
        try {
            Supplier supplier = supplierService.setEnabled(id, enabled);
            activityLogService.record(MODULE, action, (enabled ? "Activated" : "Deactivated") + " supplier "
                    + supplier.getCode() + " " + supplier.getName(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess",
                    messages.get(enabled ? "supplier.enabledMsg" : "supplier.disabledMsg", supplier.getName()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to change status of supplier " + codeOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/suppliers/" + id;
    }

    private String codeOf(UUID id) {
        try {
            return supplierService.findById(id).getCode();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }

    private String form(Model model, SupplierDto dto) {
        Supplier current = dto.getId() == null ? null : supplierService.findById(dto.getId());
        model.addAttribute("supplierDto", dto);
        model.addAttribute("supplierEntity", current);
        model.addAttribute("countryList", countries.options());
        model.addAttribute("currencies", supplierService.currenciesFor(current));
        model.addAttribute("incoterms", Incoterm.values());
        return "suppliers/form";
    }

    private String invalid(Model model, SupplierDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    /** A business rule refused the form: show it next to its field, or as a toast. */
    private String rejected(Model model, SupplierDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }
}
