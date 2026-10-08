package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.ProductDto;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.GlassType;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.GlassProducts;
import com.ntaganira.heritier.iWarehouse.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
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
 * - File      : ProductController.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Glass Products screens (MD-01): list with filters, detail with History, add, edit,
 *               activate and deactivate. PAGE_PRODUCTS + PERM_VIEW_PRODUCT; changes PERM_MANAGE_PRODUCT.
 * </pre>
 */
@Controller
@RequestMapping("/products")
public class ProductController {

    static final String MODULE = "Products";

    private final ProductService productService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final Messages messages;

    public ProductController(ProductService productService, DataChangeService dataChangeService,
                             ActivityLogService activityLogService, Messages messages) {
        this.productService = productService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.messages = messages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_PRODUCTS') and hasAuthority('PERM_VIEW_PRODUCT')")
    public String list(@RequestParam(required = false) String search,
                       @RequestParam(required = false) GlassType type,
                       @RequestParam(required = false) BigDecimal thickness,
                       @RequestParam(required = false) String status,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        Page<Product> products = productService.findPage(search, type, thickness, status, Paging.page(page), Paging.SIZE);
        BigDecimal density = productService.density();
        Map<UUID, BigDecimal> weights = new HashMap<>();
        products.forEach(p -> weights.put(p.getId(), GlassProducts.weightPerM2(p.getThicknessMm(), density)));
        model.addAttribute("products", products);
        model.addAttribute("weights", weights);
        model.addAttribute("glassTypes", GlassType.values());
        model.addAttribute("thicknesses", productService.thicknesses());
        model.addAttribute("search", search);
        model.addAttribute("type", type);
        model.addAttribute("thickness", thickness);
        model.addAttribute("status", status);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "type", type,
                "thickness", thickness == null ? null : thickness.toPlainString(), "status", status));
        return "products/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTS') and hasAuthority('PERM_VIEW_PRODUCT')")
    public String view(@PathVariable UUID id, @RequestParam(defaultValue = "details") String tab,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        String open = List.of("details", "history").contains(tab) ? tab : "details";
        Product product = productService.findById(id);
        model.addAttribute("product", product);
        model.addAttribute("weightPerM2", GlassProducts.weightPerM2(product.getThicknessMm(), productService.density()));
        model.addAttribute("density", productService.density());
        model.addAttribute("history", dataChangeService.history("Product", id.toString(), Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "products/view";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTS') and hasAuthority('PERM_MANAGE_PRODUCT')")
    public String createForm(Model model) {
        ProductDto dto = new ProductDto();
        dto.setTaxCategoryId(productService.defaultTaxCategoryId());
        return form(model, dto);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTS') and hasAuthority('PERM_MANAGE_PRODUCT')")
    public String create(@Valid @ModelAttribute("productDto") ProductDto dto, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Product product = productService.create(dto);
            activityLogService.record(MODULE, "CREATE_PRODUCT", "Added glass product " + product.getCode() + " ("
                    + describe(product) + ", VAT " + product.getTaxCategory().getCode() + ")", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("product.created", product.getCode()));
            return "redirect:/products/" + product.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_PRODUCT", "Failed to add glass product " + dto.getGlassType() + " "
                    + dto.getThicknessMm() + " mm: " + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTS') and hasAuthority('PERM_MANAGE_PRODUCT')")
    public String editForm(@PathVariable UUID id, Model model) {
        Product product = productService.findById(id);
        ProductDto dto = new ProductDto();
        dto.setId(id);
        dto.setCode(product.getCode());
        dto.setTaxCategoryId(product.getTaxCategory().getId());
        dto.setReorderLevelM2(product.getReorderLevelM2() == null ? null : product.getReorderLevelM2().stripTrailingZeros());
        dto.setNotes(product.getNotes());
        return form(model, withIdentity(dto, product));
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTS') and hasAuthority('PERM_MANAGE_PRODUCT')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("productDto") ProductDto dto,
                         BindingResult result, Model model, RedirectAttributes redirect) {
        dto.setId(id);
        withIdentity(dto, productService.findById(id)); // fixed after creation
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Product product = productService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_PRODUCT", "Updated glass product " + product.getCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("product.updated", product.getCode()));
            return "redirect:/products/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_PRODUCT", "Failed to update glass product " + codeOf(id) + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTS') and hasAuthority('PERM_MANAGE_PRODUCT')")
    public String disable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, false, redirect);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('PAGE_PRODUCTS') and hasAuthority('PERM_MANAGE_PRODUCT')")
    public String enable(@PathVariable UUID id, RedirectAttributes redirect) {
        return setEnabled(id, true, redirect);
    }

    private String setEnabled(UUID id, boolean enabled, RedirectAttributes redirect) {
        String action = enabled ? "ENABLE_PRODUCT" : "DISABLE_PRODUCT";
        try {
            Product product = productService.setEnabled(id, enabled);
            activityLogService.record(MODULE, action,
                    (enabled ? "Activated" : "Deactivated") + " glass product " + product.getCode(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess",
                    messages.get(enabled ? "product.enabledMsg" : "product.disabledMsg", product.getCode()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, action, "Failed to change status of glass product " + codeOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/products/" + id;
    }

    /** The product code for the activity log, or the id if the product does not exist. */
    private String codeOf(UUID id) {
        try {
            return productService.findById(id).getCode();
        } catch (NotFoundException e) {
            return id.toString();
        }
    }

    /** "TINTED Bronze 6 mm" for the activity log (English, like the rest of the log). */
    private static String describe(Product product) {
        return product.getGlassType() + (product.getVariant() == null ? "" : " " + product.getVariant())
                + " " + product.getThicknessLabel() + " mm";
    }

    private static ProductDto withIdentity(ProductDto dto, Product product) {
        dto.setGlassType(product.getGlassType());
        dto.setVariant(product.getVariant());
        dto.setThicknessMm(product.getThicknessMm().stripTrailingZeros());
        return dto;
    }

    private String form(Model model, ProductDto dto) {
        Product current = dto.getId() == null ? null : productService.findById(dto.getId());
        model.addAttribute("productDto", dto);
        BigDecimal density = productService.density();
        model.addAttribute("productEntity", current);
        model.addAttribute("weightPerM2", current == null ? null : GlassProducts.weightPerM2(current.getThicknessMm(), density));
        model.addAttribute("glassTypes", GlassType.values());
        model.addAttribute("taxCategories", productService.taxCategoriesFor(current));
        model.addAttribute("density", density);
        return "products/form";
    }

    private String invalid(Model model, ProductDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    /** A business rule refused the form: show it next to its field, or as a toast. */
    private String rejected(Model model, ProductDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }
}
