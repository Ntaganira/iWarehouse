package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.MasterDataReview;
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
 * - File      : MasterDataReviewRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The monthly reviews of master data changes: a month's, and the latest first.
 * </pre>
 */
public interface MasterDataReviewRepository extends JpaRepository<MasterDataReview, UUID> {

    Optional<MasterDataReview> findByPeriodStart(LocalDate periodStart);

    Page<MasterDataReview> findAllByOrderByPeriodStartDesc(Pageable pageable);
}
