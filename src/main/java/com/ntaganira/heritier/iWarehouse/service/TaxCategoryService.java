package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.TaxCategoryDto;
import com.ntaganira.heritier.iWarehouse.entity.TaxCategory;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.TaxCategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TaxCategoryService.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : VAT categories (TAX-01, ADM-03). Exactly one active category is the default for new
 *               products. Categories are deactivated, never deleted. A rate change applies to new
 *               documents only: invoices keep the rate they were issued with.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TaxCategoryService {

    private final TaxCategoryRepository repo;

    public TaxCategoryService(TaxCategoryRepository repo) {
        this.repo = repo;
    }

    public List<TaxCategory> findAll() {
        return repo.findAllByOrderByEnabledDescCodeAsc();
    }

    /** Active categories, for product forms. */
    public List<TaxCategory> findActive() {
        return repo.findByEnabledTrueOrderByCodeAsc();
    }

    public TaxCategory findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("TaxCategory", id));
    }

    @Transactional
    public TaxCategory create(TaxCategoryDto dto) {
        if (repo.existsByCodeIgnoreCase(dto.getCode())) {
            throw BusinessException.onField("code", "tax.code.taken", dto.getCode());
        }
        TaxCategory category = new TaxCategory();
        category.setCode(dto.getCode());
        apply(category, dto);
        if (dto.isDefaultCategory()) {
            makeDefault(category);
        }
        return repo.save(category);
    }

    @Transactional
    public TaxCategory update(UUID id, TaxCategoryDto dto) {
        TaxCategory category = findById(id);
        if (category.isDefaultCategory() && !dto.isDefaultCategory()) {
            throw BusinessException.onField("defaultCategory", "tax.default.keep");
        }
        if (!category.isEnabled() && dto.isDefaultCategory()) {
            throw BusinessException.onField("defaultCategory", "tax.default.inactive");
        }
        apply(category, dto);
        if (dto.isDefaultCategory() && !category.isDefaultCategory()) {
            makeDefault(category);
        }
        return category;
    }

    @Transactional
    public TaxCategory setEnabled(UUID id, boolean enabled) {
        TaxCategory category = findById(id);
        if (!enabled && category.isDefaultCategory()) {
            throw BusinessException.of("tax.default.disable", category.getCode());
        }
        category.setEnabled(enabled);
        return category;
    }

    private static void apply(TaxCategory category, TaxCategoryDto dto) {
        category.setName(dto.getName().trim());
        category.setRate(dto.getRate());
        category.setEbmCode(dto.getEbmCode());
        category.setDescription(StringUtils.hasText(dto.getDescription()) ? dto.getDescription().trim() : null);
    }

    /**
     * Moves the default flag to this category. The old default is cleared and flushed first, because
     * a unique index allows only one default row at any moment.
     */
    private void makeDefault(TaxCategory category) {
        repo.findByDefaultCategoryTrue()
                .filter(current -> !current.getId().equals(category.getId()))
                .ifPresent(current -> {
                    current.setDefaultCategory(false);
                    repo.saveAndFlush(current);
                });
        category.setDefaultCategory(true);
    }
}
