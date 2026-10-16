package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.InventoryKpiReview;
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
 * - File      : InventoryKpiReviewRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The formal reviews of inventory accuracy (IMP 19), one per month that closed a run below target.
 * </pre>
 */
public interface InventoryKpiReviewRepository extends JpaRepository<InventoryKpiReview, UUID> {

    Optional<InventoryKpiReview> findByPeriodStart(LocalDate periodStart);

    Page<InventoryKpiReview> findAllByOrderByPeriodStartDesc(Pageable pageable);
}
