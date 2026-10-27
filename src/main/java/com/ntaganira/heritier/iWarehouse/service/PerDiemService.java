package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.PerDiemRateDto;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.entity.PerDiemRate;
import com.ntaganira.heritier.iWarehouse.enums.PerDiemCategory;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CurrencyRepository;
import com.ntaganira.heritier.iWarehouse.repository.PerDiemRateRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PerDiemService.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Per diem rates (CMP 08): one per travel category (local, upcountry, regional, international) in its
 *               currency, the meals and incidentals of a day and the accommodation of a night, changed on screen
 *               (PERM_MANAGE_PER_DIEM, in the change log). A travel advance is worked out from them.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class PerDiemService {

    private final PerDiemRateRepository repo;
    private final CurrencyRepository currencyRepo;

    public PerDiemService(PerDiemRateRepository repo, CurrencyRepository currencyRepo) {
        this.repo = repo;
        this.currencyRepo = currencyRepo;
    }

    /** Every category's rate, in the categories' order. */
    public List<PerDiemRate> rates() {
        return repo.findAll().stream().sorted(Comparator.comparing(PerDiemRate::getCategory)).toList();
    }

    public PerDiemRate find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("PerDiemRate", id));
    }

    public PerDiemRate of(PerDiemCategory category) {
        return repo.findByCategory(category).orElseThrow(() -> BusinessException.onField("perDiemCategory", "perDiem.missing", category.name()));
    }

    public List<Currency> currencies() {
        return currencyRepo.findAll(Sort.by("code")).stream().filter(Currency::isEnabled).toList();
    }

    public PerDiemRateDto formOf(PerDiemRate rate) {
        PerDiemRateDto dto = new PerDiemRateDto();
        dto.setCurrencyCode(rate.getCurrencyCode());
        dto.setMeal(rate.getMeal());
        dto.setAccommodation(rate.getAccommodation());
        dto.setIncidentals(rate.getIncidentals());
        return dto;
    }

    /** Changes a category's rate (the change log keeps the old one). */
    @Transactional
    public PerDiemRate update(UUID id, PerDiemRateDto dto) {
        PerDiemRate rate = find(id);
        boolean known = currencies().stream().anyMatch(c -> c.getCode().equals(dto.getCurrencyCode()));
        if (!known) {
            throw BusinessException.onField("currencyCode", "perDiem.currency.unknown", dto.getCurrencyCode());
        }
        rate.setCurrencyCode(dto.getCurrencyCode());
        rate.setMeal(dto.getMeal().setScale(2, RoundingMode.HALF_UP));
        rate.setAccommodation(dto.getAccommodation().setScale(2, RoundingMode.HALF_UP));
        rate.setIncidentals(dto.getIncidentals().setScale(2, RoundingMode.HALF_UP));
        return rate;
    }
}
