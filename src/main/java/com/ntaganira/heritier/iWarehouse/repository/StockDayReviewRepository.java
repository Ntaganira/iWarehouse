package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockDayReview;
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
 * - File      : StockDayReviewRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The daily reviews of stock movements (IMP 12).
 * </pre>
 */
public interface StockDayReviewRepository extends JpaRepository<StockDayReview, UUID> {

    Optional<StockDayReview> findByDay(LocalDate day);

    Page<StockDayReview> findAllByOrderByDayDesc(Pageable pageable);

    /** The days signed off in a period (reconciliation compliance, IMP 19). */
    java.util.List<StockDayReview> findByDayBetween(LocalDate from, LocalDate to);
}
