package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.config.Countries;
import com.ntaganira.heritier.iWarehouse.dto.SupplierDto;
import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.entity.Supplier;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CurrencyRepository;
import com.ntaganira.heritier.iWarehouse.repository.PurchaseOrderRepository;
import com.ntaganira.heritier.iWarehouse.repository.SupplierRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : SupplierService.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Suppliers (MD-05). Names are unique (ignoring case). A new supplier, or a change of
 *               currency, needs an active currency; a Rwandan supplier's TIN has 9 digits. Codes come
 *               from DocumentNumberService (SUP-WH-0001).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class SupplierService {

    private final SupplierRepository repo;
    private final CurrencyRepository currencyRepo;
    private final DocumentNumberService numbers;
    private final Countries countries;
    private final PurchaseOrderRepository orderRepo;

    public SupplierService(SupplierRepository repo, CurrencyRepository currencyRepo, DocumentNumberService numbers,
                           Countries countries, PurchaseOrderRepository orderRepo) {
        this.repo = repo;
        this.currencyRepo = currencyRepo;
        this.numbers = numbers;
        this.countries = countries;
        this.orderRepo = orderRepo;
    }

    public Page<Supplier> findPage(String search, String country, String currency, String status, int page, int size) {
        Specification<Supplier> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(
                        cb.like(cb.lower(root.get("name")), term),
                        cb.like(cb.lower(root.get("code")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("contactName"), "")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("phone"), "")), term),
                        cb.like(cb.lower(cb.coalesce(root.get("email"), "")), term)));
            }
            if (StringUtils.hasText(country)) {
                p = cb.and(p, cb.equal(root.get("countryCode"), country));
            }
            if (StringUtils.hasText(currency)) {
                p = cb.and(p, cb.equal(root.get("currencyCode"), currency));
            }
            if ("active".equalsIgnoreCase(status)) {
                p = cb.and(p, cb.isTrue(root.get("enabled")));
            } else if ("inactive".equalsIgnoreCase(status)) {
                p = cb.and(p, cb.isFalse(root.get("enabled")));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by("name").and(Sort.by("id"))));
    }

    public List<String> countryCodes() {
        return repo.findCountryCodes();
    }

    public List<String> currencyCodes() {
        return repo.findCurrencyCodes();
    }

    public Supplier findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Supplier", id));
    }

    /** Active currencies, plus the supplier's own one if it has since been deactivated. */
    public List<Currency> currenciesFor(Supplier current) {
        return currencyRepo.findAllByOrderByBaseCurrencyDescEnabledDescCodeAsc().stream()
                .filter(c -> c.isEnabled() || (current != null && c.getCode().equals(current.getCurrencyCode())))
                .toList();
    }

    @Transactional
    public Supplier create(SupplierDto dto) {
        check(dto, null);
        Supplier supplier = new Supplier();
        supplier.setCode(numbers.next(DocumentType.SUPPLIER));
        apply(supplier, dto);
        return repo.save(supplier);
    }

    @Transactional
    public Supplier update(UUID id, SupplierDto dto) {
        Supplier supplier = findById(id);
        check(dto, supplier);
        apply(supplier, dto);
        return supplier;
    }

    @Transactional
    public Supplier setEnabled(UUID id, boolean enabled) {
        Supplier supplier = findById(id);
        if (!enabled) {
            long open = orderRepo.countBySupplier_IdAndStatusIn(id, PurchaseOrderService.OPEN);
            if (open > 0) {
                throw BusinessException.of("supplier.disable.openOrders", supplier.getName(), open);
            }
        }
        supplier.setEnabled(enabled);
        return supplier;
    }

    private void check(SupplierDto dto, Supplier current) {
        String name = dto.getName().trim();
        boolean nameTaken = current == null ? repo.existsByNameIgnoreCase(name) : repo.existsByNameIgnoreCaseAndIdNot(name, current.getId());
        if (nameTaken) {
            throw BusinessException.onField("name", "supplier.name.taken", name);
        }
        if (!countries.isValid(dto.getCountryCode())) {
            throw BusinessException.onField("countryCode", "supplier.country.required");
        }
        Currency currency = currencyRepo.findByCode(dto.getCurrencyCode())
                .orElseThrow(() -> BusinessException.onField("currencyCode", "supplier.currency.required"));
        boolean sameCurrency = current != null && current.getCurrencyCode().equals(currency.getCode());
        if (!currency.isEnabled() && !sameCurrency) {
            throw BusinessException.onField("currencyCode", "supplier.currency.inactive", currency.getCode());
        }
        String tin = PartyRules.normalizeTin(dto.getTin());
        if (tin != null && PartyRules.RWANDA.equals(dto.getCountryCode()) && !PartyRules.isRwandaTin(tin)) {
            throw BusinessException.onField("tin", "supplier.tin.rwanda");
        }
    }

    private static void apply(Supplier supplier, SupplierDto dto) {
        supplier.setName(dto.getName().trim());
        supplier.setCountryCode(dto.getCountryCode());
        supplier.setCurrencyCode(dto.getCurrencyCode());
        supplier.setIncoterm(dto.getIncoterm());
        supplier.setTin(PartyRules.normalizeTin(dto.getTin()));
        supplier.setPaymentTermsDays(dto.getPaymentTermsDays());
        supplier.setContactName(PartyRules.clean(dto.getContactName()));
        supplier.setPhone(PartyRules.clean(dto.getPhone()));
        supplier.setEmail(PartyRules.clean(dto.getEmail()));
        supplier.setAddress(PartyRules.clean(dto.getAddress()));
        supplier.setNotes(PartyRules.clean(dto.getNotes()));
    }
}
