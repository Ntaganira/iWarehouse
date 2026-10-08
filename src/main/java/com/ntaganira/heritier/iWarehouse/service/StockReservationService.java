package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.CustomerRepository;
import com.ntaganira.heritier.iWarehouse.repository.StockUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : StockReservationService.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Reserving a unit for a customer and releasing it (INV-05), from the unit's page. The
 *               counter POS will sell reserved units to their customer.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class StockReservationService {

    private final StockUnitRepository unitRepo;
    private final CustomerRepository customerRepo;
    private final StockService stockService;

    public StockReservationService(StockUnitRepository unitRepo, CustomerRepository customerRepo, StockService stockService) {
        this.unitRepo = unitRepo;
        this.customerRepo = customerRepo;
        this.stockService = stockService;
    }

    /** Customers a unit can be reserved for: active and not the anonymous walk-in. */
    public List<Customer> customers() {
        return customerRepo.findByEnabledTrueOrderByNameAsc().stream().filter(c -> !c.isDefaultCustomer()).toList();
    }

    @Transactional
    public StockUnit reserve(UUID unitId, UUID customerId, String note) {
        StockUnit unit = unitRepo.findById(unitId).orElseThrow(() -> new NotFoundException("StockUnit", unitId));
        Customer customer = customerId == null ? null : customerRepo.findById(customerId).orElse(null);
        if (customer == null || !customer.isEnabled() || customer.isDefaultCustomer()) {
            throw BusinessException.of("reservation.customer.required");
        }
        stockService.reserve(unit, customer, PartyRules.clean(note));
        return unit;
    }

    @Transactional
    public StockUnit release(UUID unitId, String reason) {
        StockUnit unit = unitRepo.findById(unitId).orElseThrow(() -> new NotFoundException("StockUnit", unitId));
        stockService.release(unit, reason.trim());
        return unit;
    }
}
