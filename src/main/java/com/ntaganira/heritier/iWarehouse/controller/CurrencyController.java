package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.dto.CurrencyDto;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.CurrencyService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.ExchangeRateService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : CurrencyController.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Currencies &amp; Rates screen (ACC-02): latest rate per currency, the rate list with
 *               filters, the currency list and History on tabs; add, edit, activate and deactivate
 *               currencies. PAGE_CURRENCIES + PERM_VIEW_CURRENCY; currency changes PERM_MANAGE_CURRENCY.
 * </pre>
 */
@Controller
@RequestMapping("/currencies")
public class CurrencyController {

    static final String MODULE = "Currencies";
    private static final Set<String> TABS = Set.of("rates", "currencies", "history");
    private static final List<String> AUDITED_TYPES = List.of("Currency", "ExchangeRate");
    private static final int PAGE_SIZE = 20;

    private final CurrencyService currencyService;
    private final ExchangeRateService rateService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public CurrencyController(CurrencyService currencyService, ExchangeRateService rateService,
                              DataChangeService dataChangeService, ActivityLogService activityLogService,
                              Messages messages) {
        this.currencyService = currencyService;
        this.rateService = rateService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_CURRENCIES') and hasAuthority('PERM_VIEW_CURRENCY')")
    public String view(@RequestParam(defaultValue = "rates") String tab,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String currency,
                       @RequestParam(required = false) RateSource source,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       Model model) {
        String activeTab = TABS.contains(tab) ? tab : "rates";
        model.addAttribute("tab", activeTab);
        model.addAttribute("latestRates", rateService.latestRates());
        model.addAttribute("defaultSource", rateService.defaultSource());
        model.addAttribute("maxAgeDays", rateService.maxAgeDays());
        model.addAttribute("rates", rateService.findPage(currency, source, from, to,
                "rates".equals(activeTab) ? Math.max(page, 0) : 0, PAGE_SIZE));
        model.addAttribute("currencies", currencyService.findAll());
        model.addAttribute("sources", RateSource.values());
        model.addAttribute("currency", currency);
        model.addAttribute("source", source);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("ratesQuery", QueryString.of("tab", "rates", "currency", currency, "source", source,
                "from", from, "to", to));
        if (AppUserPrincipal.currentHas("PERM_VIEW_DATA_CHANGES")) {
            model.addAttribute("history", dataChangeService.historyOfTypes(AUDITED_TYPES,
                    "history".equals(activeTab) ? Math.max(page, 0) : 0, PAGE_SIZE));
        }
        return "currencies/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_CURRENCIES') and hasAuthority('PERM_MANAGE_CURRENCY')")
    public String createForm(Model model) {
        return form(model, new CurrencyDto());
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_CURRENCIES') and hasAuthority('PERM_MANAGE_CURRENCY')")
    public String create(@Valid @ModelAttribute("currencyDto") CurrencyDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Currency currency = currencyService.create(dto);
            activityLogService.record(MODULE, "CREATE_CURRENCY", "Added currency " + currency.getCode()
                    + " (" + currency.getName() + ", " + currency.getDecimals() + " decimals)", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("currency.created", currency.getCode()));
            return "redirect:/currencies?tab=currencies";
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_CURRENCY", "Failed to add currency " + dto.getCode() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_CURRENCIES') and hasAuthority('PERM_MANAGE_CURRENCY')")
    public String editForm(@PathVariable UUID id, Model model) {
        Currency currency = currencyService.findById(id);
        CurrencyDto dto = new CurrencyDto();
        dto.setId(id);
        dto.setCode(currency.getCode());
        dto.setName(currency.getName());
        dto.setSymbol(currency.getSymbol());
        dto.setDecimals(currency.getDecimals());
        return form(model, dto);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_CURRENCIES') and hasAuthority('PERM_MANAGE_CURRENCY')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("currencyDto") CurrencyDto dto,
                         BindingResult result, Model model, RedirectAttributes redirect) {
        dto.setId(id);
        dto.setCode(currencyService.findById(id).getCode()); // fixed after creation
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Currency currency = currencyService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_CURRENCY", "Updated currency " + currency.getCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("currency.updated", currency.getCode()));
            return "redirect:/currencies?tab=currencies";
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_CURRENCY", "Failed to update currency " + dto.getCode() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_CURRENCIES') and hasAuthority('PERM_MANAGE_CURRENCY')")
    public String disable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, false, redirect);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_CURRENCIES') and hasAuthority('PERM_MANAGE_CURRENCY')")
    public String enable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, true, redirect);
    }

    private String setEnabled(UUID id, boolean enabled, RedirectAttributes redirect) {
        String action = enabled ? "ENABLE_CURRENCY" : "DISABLE_CURRENCY";
        try {
            Currency currency = currencyService.setEnabled(id, enabled);
            activityLogService.record(MODULE, action,
                    (enabled ? "Activated" : "Deactivated") + " currency " + currency.getCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess",
                    messages.get(enabled ? "currency.enabledMsg" : "currency.disabledMsg", currency.getCode()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to change status of currency " + currencyService.findById(id).getCode() + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/currencies?tab=currencies";
    }

    private String form(Model model, CurrencyDto dto) {
        model.addAttribute("currencyDto", dto);
        if (dto.getId() != null) {
            Currency currency = currencyService.findById(dto.getId());
            model.addAttribute("currencyEntity", currency);
            model.addAttribute("history", dataChangeService.history("Currency", dto.getId().toString(), 0, 10));
        }
        return "currencies/form";
    }

    private String invalid(Model model, CurrencyDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    /** A business rule refused the form: show it next to its field, or as a toast. */
    private String rejected(Model model, CurrencyDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }
}
