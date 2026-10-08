package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.ProductDto;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.TaxCategory;
import com.ntaganira.heritier.iWarehouse.enums.GlassType;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.ProductRepository;
import com.ntaganira.heritier.iWarehouse.repository.PurchaseOrderLineRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import com.ntaganira.heritier.iWarehouse.repository.TaxCategoryRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : ProductService.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Glass products (MD-01). One product per glass type, colour/finish and thickness. Type,
 *               colour/finish and thickness are fixed after creation; code, VAT category, reorder level
 *               and notes can change. A new product needs an active VAT category (TAX-01).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository repo;
    private final TaxCategoryRepository taxRepo;
    private final SettingService settingService;
    private final StockUnitRepository unitRepo;
    private final PurchaseOrderLineRepository orderLineRepo;

    public ProductService(ProductRepository repo, TaxCategoryRepository taxRepo, SettingService settingService,
                          StockUnitRepository unitRepo, PurchaseOrderLineRepository orderLineRepo) {
        this.repo = repo;
        this.taxRepo = taxRepo;
        this.settingService = settingService;
        this.unitRepo = unitRepo;
        this.orderLineRepo = orderLineRepo;
    }

    public Page<Product> findPage(String search, GlassType type, BigDecimal thickness, String status, int page, int size) {
        Specification<Product> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("code")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("variant"), "")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("notes"), "")), term)));
            }
            if (type != null) {
                p = cb.and(p, cb.equal(root.get("glassType"), type));
            }
            if (thickness != null) {
                p = cb.and(p, cb.equal(root.get("thicknessMm"), thickness));
            }
            if ("active".equalsIgnoreCase(status)) {
                p = cb.and(p, cb.isTrue(root.get("enabled")));
            } else if ("inactive".equalsIgnoreCase(status)) {
                p = cb.and(p, cb.isFalse(root.get("enabled")));
            }
            return p;
        };
        Sort sort = Sort.by("glassType").and(Sort.by("variant")).and(Sort.by("thicknessMm")).and(Sort.by("id"));
        return repo.findAll(spec, PageRequest.of(page, size, sort));
    }

    public List<BigDecimal> thicknesses() {
        return repo.findThicknesses();
    }

    public Product findById(UUID id) {
        return repo.findWithTaxCategoryById(id).orElseThrow(() -> new NotFoundException("Product", id));
    }

    /** kg per m² per mm of thickness (Settings, INV-02). */
    public BigDecimal density() {
        return settingService.getDecimal(SettingKey.GLASS_DENSITY);
    }

    /** Active VAT categories, plus the product's own one if it has since been deactivated. */
    public List<TaxCategory> taxCategoriesFor(Product current) {
        List<TaxCategory> list = new ArrayList<>(taxRepo.findByEnabledTrueOrderByCodeAsc());
        if (current != null && !current.getTaxCategory().isEnabled()) {
            list.add(current.getTaxCategory());
        }
        return list;
    }

    public UUID defaultTaxCategoryId() {
        return taxRepo.findByDefaultCategoryTrue().map(TaxCategory::getId).orElse(null);
    }

    @Transactional
    public Product create(ProductDto dto) {
        String variant = GlassProducts.normalizeVariant(dto.getVariant());
        BigDecimal thickness = dto.getThicknessMm();
        if (repo.existsIdentity(dto.getGlassType(), variant == null ? "" : variant, thickness)) {
            throw BusinessException.onField("thicknessMm", "product.exists");
        }
        String code;
        if (StringUtils.hasText(dto.getCode())) {
            code = dto.getCode();
            if (repo.existsByCode(code)) {
                throw BusinessException.onField("code", "product.code.taken", code);
            }
        } else {
            code = freeCode(GlassProducts.suggestCode(dto.getGlassType(), variant, thickness));
        }
        Product product = new Product();
        product.setCode(code);
        product.setGlassType(dto.getGlassType());
        product.setVariant(variant);
        product.setThicknessMm(thickness);
        product.setTaxCategory(taxCategory(dto.getTaxCategoryId(), null));
        apply(product, dto);
        return repo.save(product);
    }

    @Transactional
    public Product update(UUID id, ProductDto dto) {
        Product product = findById(id);
        if (StringUtils.hasText(dto.getCode()) && !dto.getCode().equals(product.getCode())) {
            if (repo.existsByCodeAndIdNot(dto.getCode(), id)) {
                throw BusinessException.onField("code", "product.code.taken", dto.getCode());
            }
            product.setCode(dto.getCode());
        }
        product.setTaxCategory(taxCategory(dto.getTaxCategoryId(), product.getTaxCategory()));
        apply(product, dto);
        return product;
    }

    @Transactional
    public Product setEnabled(UUID id, boolean enabled) {
        Product product = findById(id);
        if (!enabled) {
            long held = unitRepo.countByProduct_IdAndStatusIn(id, StockStatus.onHand());
            if (held > 0) {
                throw BusinessException.of("product.disable.stock", product.getCode(), held);
            }
            long ordered = orderLineRepo.countByProduct_IdAndPurchaseOrder_StatusIn(id, PurchaseOrderService.OPEN);
            if (ordered > 0) {
                throw BusinessException.of("product.disable.ordered", product.getCode(), ordered);
            }
        }
        product.setEnabled(enabled);
        return product;
    }

    private static void apply(Product product, ProductDto dto) {
        product.setReorderLevelM2(dto.getReorderLevelM2());
        product.setNotes(StringUtils.hasText(dto.getNotes()) ? dto.getNotes().trim() : null);
    }

    /** The VAT category must be active, unless the product already had it. */
    private TaxCategory taxCategory(UUID id, TaxCategory current) {
        TaxCategory tax = taxRepo.findById(id)
                .orElseThrow(() -> BusinessException.onField("taxCategoryId", "product.taxCategory.required"));
        boolean unchanged = current != null && current.getId().equals(tax.getId());
        if (!tax.isEnabled() && !unchanged) {
            throw BusinessException.onField("taxCategoryId", "product.taxCategory.inactive", tax.getCode());
        }
        return tax;
    }

    /** The suggested code, or the same with -2, -3... when another product already uses it. */
    private String freeCode(String base) {
        if (!repo.existsByCode(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String suffix = "-" + n;
            String code = base.substring(0, Math.min(base.length(), 20 - suffix.length())) + suffix;
            if (!repo.existsByCode(code)) {
                return code;
            }
        }
    }
}
