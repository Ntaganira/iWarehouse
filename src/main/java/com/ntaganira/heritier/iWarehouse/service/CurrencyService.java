package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.CurrencyDto;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CurrencyRepository;
import com.ntaganira.heritier.iWarehouse.repository.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : CurrencyService.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Currencies (ACC-02). RWF is the base currency, set by migration: it stays active and
 *               keeps 0 decimals. Other currencies are added or activated when the business starts
 *               buying in them, and deactivated rather than deleted, once no active supplier invoices
 *               in them.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class CurrencyService {

    private final CurrencyRepository repo;
    private final SupplierRepository supplierRepo;

    public CurrencyService(CurrencyRepository repo, SupplierRepository supplierRepo) {
        this.repo = repo;
        this.supplierRepo = supplierRepo;
    }

    /** Base currency first, then active ones. */
    public List<Currency> findAll() {
        return repo.findAllByOrderByBaseCurrencyDescEnabledDescCodeAsc();
    }

    /** Active currencies other than RWF: the ones that need exchange rates. */
    public List<Currency> findActiveForeign() {
        return repo.findByEnabledTrueAndBaseCurrencyFalseOrderByCodeAsc();
    }

    public Currency findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Currency", id));
    }

    public Currency base() {
        return repo.findByBaseCurrencyTrue().orElseThrow(() -> new IllegalStateException("No base currency (V7)"));
    }

    @Transactional
    public Currency create(CurrencyDto dto) {
        if (repo.existsByCode(dto.getCode())) {
            throw BusinessException.onField("code", "currency.code.taken", dto.getCode());
        }
        Currency currency = new Currency();
        currency.setCode(dto.getCode());
        apply(currency, dto);
        return repo.save(currency);
    }

    @Transactional
    public Currency update(UUID id, CurrencyDto dto) {
        Currency currency = findById(id);
        if (currency.isBaseCurrency() && dto.getDecimals() != currency.getDecimals()) {
            throw BusinessException.onField("decimals", "currency.base.decimals", currency.getCode());
        }
        apply(currency, dto);
        return currency;
    }

    @Transactional
    public Currency setEnabled(UUID id, boolean enabled) {
        Currency currency = findById(id);
        if (!enabled && currency.isBaseCurrency()) {
            throw BusinessException.of("currency.base.disable", currency.getCode());
        }
        if (!enabled) {
            // Purchase orders of these suppliers are raised in their currency (PRC-01)
            long suppliers = supplierRepo.countByCurrencyCodeAndEnabledTrue(currency.getCode());
            if (suppliers > 0) {
                throw BusinessException.of("currency.disable.suppliers", currency.getCode(), suppliers);
            }
        }
        currency.setEnabled(enabled);
        return currency;
    }

    private static void apply(Currency currency, CurrencyDto dto) {
        currency.setName(dto.getName().trim());
        currency.setSymbol(dto.getSymbol().trim());
        currency.setDecimals(dto.getDecimals());
    }
}
