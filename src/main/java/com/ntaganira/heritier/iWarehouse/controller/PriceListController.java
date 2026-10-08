package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.dto.PriceListDto;
import com.ntaganira.heritier.iWarehouse.dto.ProcessingServiceDto;
import com.ntaganira.heritier.iWarehouse.entity.PriceList;
import com.ntaganira.heritier.iWarehouse.entity.ProcessingService;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.ChargeUnit;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.PriceListService;
import com.ntaganira.heritier.iWarehouse.service.Pricing;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : PriceListController.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Price Lists screens (MD-06): lists and processing services, a list's prices, customers
 *               and price history, editing all prices of a list on one page, add/edit lists and services,
 *               default list. PAGE_PRICE_LISTS + PERM_VIEW_PRICE_LIST; changes PERM_MANAGE_PRICE_LIST.
 * </pre>
 */
@Controller
@RequestMapping("/price-lists")
public class PriceListController {

    static final String MODULE = "Price Lists";
    private static final Set<String> TABS = Set.of("prices", "processing", "customers", "history");
    private static final int HISTORY_SIZE = 25;
    private static final String PRODUCT_FIELD = "p_";
    private static final String SERVICE_FIELD = "s_";

    private final PriceListService priceListService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public PriceListController(PriceListService priceListService, ActivityLogService activityLogService, Messages messages) {
        this.priceListService = priceListService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_VIEW_PRICE_LIST')")
    public String list(Model model) {
        model.addAttribute("summaries", priceListService.summaries());
        model.addAttribute("services", priceListService.services());
        model.addAttribute("productCount", priceListService.activeProductCount());
        model.addAttribute("settingMinArea", priceListService.settingMinChargeableArea());
        return "price-lists/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_VIEW_PRICE_LIST')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "prices") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        PriceList list = priceListService.findById(id);
        model.addAttribute("list", list);
        model.addAttribute("tab", TABS.contains(tab) ? tab : "prices");
        model.addAttribute("defaultList", list.isDefaultList() ? list : priceListService.defaultList());
        model.addAttribute("minArea", priceListService.minChargeableArea(list));
        model.addAttribute("productRows", priceListService.productRows(list));
        model.addAttribute("serviceRows", priceListService.serviceRows(list));
        model.addAttribute("customers", priceListService.customers(id));
        if (AppUserPrincipal.currentHas("PERM_VIEW_DATA_CHANGES")) {
            model.addAttribute("history", priceListService.history(list, Math.max(page, 0), HISTORY_SIZE));
            model.addAttribute("subjects", priceListService.historySubjects(id));
        }
        return "price-lists/view";
    }

    // ---------------------------------------------------------------- prices

    @GetMapping("/{id}/prices")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String pricesForm(@PathVariable UUID id, Model model) {
        return pricesPage(model, priceListService.findById(id), null, Map.of());
    }

    /** Fields are p_{productId} and s_{serviceId}; blank clears a price. Nothing is saved while one is wrong. */
    @PostMapping("/{id}/prices")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String savePrices(@PathVariable UUID id, @RequestParam Map<String, String> params, Model model,
                             RedirectAttributes redirect) {
        PriceList list = priceListService.findById(id);
        Map<UUID, BigDecimal> productPrices = new LinkedHashMap<>();
        Map<UUID, BigDecimal> servicePrices = new LinkedHashMap<>();
        Map<String, String> errors = new HashMap<>();
        params.forEach((name, value) -> {
            boolean product = name.startsWith(PRODUCT_FIELD);
            if (!product && !name.startsWith(SERVICE_FIELD)) {
                return;
            }
            UUID rowId;
            try {
                rowId = UUID.fromString(name.substring(2));
            } catch (IllegalArgumentException e) {
                return;
            }
            try {
                BigDecimal price = Pricing.parsePrice(value);
                (product ? productPrices : servicePrices).put(rowId, price);
            } catch (IllegalArgumentException e) {
                errors.put(name, messages.get(e.getMessage()));
            }
        });
        if (!errors.isEmpty()) {
            activityLogService.record(MODULE, "UPDATE_PRICES", "Failed to save prices of " + list.getCode() + ": "
                    + errors.size() + " invalid price(s)", ActivityStatus.FAILED);
            model.addAttribute("flashError", messages.get("price.fixErrors", errors.size()));
            return pricesPage(model, list, params, errors);
        }
        PriceListService.PriceUpdate update = priceListService.updatePrices(id, productPrices, servicePrices);
        if (update.total() == 0) {
            redirect.addFlashAttribute("flashSuccess", messages.get("price.nothingChanged"));
        } else {
            activityLogService.record(MODULE, "UPDATE_PRICES", "Updated prices of " + list.getCode() + ": "
                    + PriceListService.describe(update), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("price.saved", update.added(), update.changed(),
                    update.cleared(), list.getName()));
        }
        return "redirect:/price-lists/" + id;
    }

    /** {@code typed}: the submitted text per field, shown again when something was wrong. */
    private String pricesPage(Model model, PriceList list, Map<String, String> typed, Map<String, String> errors) {
        model.addAttribute("list", list);
        model.addAttribute("defaultList", list.isDefaultList() ? list : priceListService.defaultList());
        model.addAttribute("productRows", priceListService.productRows(list));
        model.addAttribute("serviceRows", priceListService.serviceRows(list));
        model.addAttribute("typed", typed);
        model.addAttribute("errors", errors);
        return "price-lists/prices";
    }

    // ---------------------------------------------------------------- lists

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String createForm(Model model) {
        return form(model, new PriceListDto());
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String create(@Valid @ModelAttribute("priceListDto") PriceListDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            PriceList list = priceListService.create(dto);
            activityLogService.record(MODULE, "CREATE_PRICE_LIST", "Added price list " + list.getCode() + " " + list.getName()
                    + (list.isPricesIncludeVat() ? " (VAT included)" : " (VAT excluded)"), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("pricelist.created", list.getName()));
            return "redirect:/price-lists/" + list.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_PRICE_LIST", "Failed to add price list " + dto.getCode() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String editForm(@PathVariable UUID id, Model model) {
        PriceList list = priceListService.findById(id);
        PriceListDto dto = new PriceListDto();
        dto.setId(id);
        dto.setCode(list.getCode());
        dto.setName(list.getName());
        dto.setPricesIncludeVat(list.isPricesIncludeVat());
        dto.setMinChargeableM2(list.getMinChargeableM2() == null ? null : list.getMinChargeableM2().stripTrailingZeros());
        dto.setNotes(list.getNotes());
        return form(model, dto);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("priceListDto") PriceListDto dto,
                         BindingResult result, Model model, RedirectAttributes redirect) {
        dto.setId(id);
        dto.setCode(priceListService.findById(id).getCode()); // fixed after creation
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            PriceList list = priceListService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_PRICE_LIST", "Updated price list " + list.getCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("pricelist.updated", list.getName()));
            return "redirect:/price-lists/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_PRICE_LIST", "Failed to update price list " + dto.getCode() + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @PostMapping("/{id}/default")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String makeDefault(@PathVariable UUID id, RedirectAttributes redirect) {
        String code = priceListService.findById(id).getCode();
        try {
            PriceList list = priceListService.makeDefault(id);
            activityLogService.record(MODULE, "UPDATE_PRICE_LIST", "Made " + list.getCode() + " the default price list",
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("pricelist.defaultMsg", list.getName()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_PRICE_LIST", "Failed to make " + code + " the default price list: " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/price-lists/" + id;
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String disable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, false, redirect);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String enable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, true, redirect);
    }

    private String setEnabled(UUID id, boolean enabled, RedirectAttributes redirect) {
        String action = enabled ? "ENABLE_PRICE_LIST" : "DISABLE_PRICE_LIST";
        String code = priceListService.findById(id).getCode();
        try {
            PriceList list = priceListService.setEnabled(id, enabled);
            activityLogService.record(MODULE, action, (enabled ? "Activated" : "Deactivated") + " price list " + list.getCode(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess",
                    messages.get(enabled ? "pricelist.enabledMsg" : "pricelist.disabledMsg", list.getName()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to change status of price list " + code + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/price-lists/" + id;
    }

    private String form(Model model, PriceListDto dto) {
        model.addAttribute("priceListDto", dto);
        model.addAttribute("listEntity", dto.getId() == null ? null : priceListService.findById(dto.getId()));
        model.addAttribute("settingMinArea", priceListService.settingMinChargeableArea());
        return "price-lists/form";
    }

    private String invalid(Model model, PriceListDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    private String rejected(Model model, PriceListDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }

    // ---------------------------------------------------------------- processing services

    @GetMapping("/services/new")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String createServiceForm(Model model) {
        ProcessingServiceDto dto = new ProcessingServiceDto();
        dto.setChargeUnit(ChargeUnit.METRE);
        return serviceForm(model, dto);
    }

    @PostMapping("/services/new")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String createService(@Valid @ModelAttribute("serviceDto") ProcessingServiceDto dto, BindingResult result,
                                Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return serviceInvalid(model, dto, result);
        }
        try {
            ProcessingService service = priceListService.createService(dto);
            activityLogService.record(MODULE, "CREATE_PROCESSING_SERVICE", "Added processing service " + service.getCode()
                    + " " + service.getName() + " (per " + service.getChargeUnit() + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("service.created", service.getName()));
            return "redirect:/price-lists#services";
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_PROCESSING_SERVICE", "Failed to add processing service " + dto.getCode()
                    + ": " + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return serviceRejected(model, dto, result, e);
        }
    }

    @GetMapping("/services/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String editServiceForm(@PathVariable UUID id, Model model) {
        ProcessingService service = priceListService.findService(id);
        ProcessingServiceDto dto = new ProcessingServiceDto();
        dto.setId(id);
        dto.setCode(service.getCode());
        dto.setName(service.getName());
        dto.setChargeUnit(service.getChargeUnit());
        return serviceForm(model, dto);
    }

    @PostMapping("/services/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String updateService(@PathVariable UUID id, @Valid @ModelAttribute("serviceDto") ProcessingServiceDto dto,
                                BindingResult result, Model model, RedirectAttributes redirect) {
        dto.setId(id);
        dto.setCode(priceListService.findService(id).getCode()); // fixed after creation
        if (result.hasErrors()) {
            return serviceInvalid(model, dto, result);
        }
        try {
            ProcessingService service = priceListService.updateService(id, dto);
            activityLogService.record(MODULE, "UPDATE_PROCESSING_SERVICE", "Updated processing service " + service.getCode(),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("service.updated", service.getName()));
            return "redirect:/price-lists#services";
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_PROCESSING_SERVICE", "Failed to update processing service " + dto.getCode()
                    + ": " + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return serviceRejected(model, dto, result, e);
        }
    }

    @PostMapping("/services/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String disableService(@PathVariable UUID id, RedirectAttributes redirect) {
        return setServiceEnabled(id, false, redirect);
    }

    @PostMapping("/services/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_PRICE_LISTS') and hasAuthority('PERM_MANAGE_PRICE_LIST')")
    public String enableService(@PathVariable UUID id, RedirectAttributes redirect) {
        return setServiceEnabled(id, true, redirect);
    }

    private String setServiceEnabled(UUID id, boolean enabled, RedirectAttributes redirect) {
        ProcessingService service = priceListService.setServiceEnabled(id, enabled);
        activityLogService.record(MODULE, enabled ? "ENABLE_PROCESSING_SERVICE" : "DISABLE_PROCESSING_SERVICE",
                (enabled ? "Activated" : "Deactivated") + " processing service " + service.getCode(), ActivityStatus.SUCCESS);
        redirect.addFlashAttribute("flashSuccess",
                messages.get(enabled ? "service.enabledMsg" : "service.disabledMsg", service.getName()));
        return "redirect:/price-lists#services";
    }

    private String serviceForm(Model model, ProcessingServiceDto dto) {
        model.addAttribute("serviceDto", dto);
        model.addAttribute("units", ChargeUnit.values());
        model.addAttribute("unitFixed", dto.getId() != null && priceListService.isPriced(priceListService.findService(dto.getId())));
        return "price-lists/service-form";
    }

    private String serviceInvalid(Model model, ProcessingServiceDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return serviceForm(model, dto);
    }

    private String serviceRejected(Model model, ProcessingServiceDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return serviceInvalid(model, dto, result);
    }
}
