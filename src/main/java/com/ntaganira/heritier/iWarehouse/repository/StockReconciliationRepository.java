package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockReconciliation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : StockReconciliationRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The monthly stock reconciliations signed off (BP 7.4, Annex I step 5).
 * </pre>
 */
public interface StockReconciliationRepository extends JpaRepository<StockReconciliation, UUID> {

    Optional<StockReconciliation> findByPeriodStart(LocalDate periodStart);

    Page<StockReconciliation> findAllByOrderByPeriodStartDesc(Pageable pageable);
}
