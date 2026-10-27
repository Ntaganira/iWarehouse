package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StockVariance;
import com.ntaganira.heritier.iWarehouse.enums.VarianceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : StockVarianceRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Count variances (IMP 16): a page of them, one with its count, the lock resolving takes, the open ones past their due day, those of a period for the reports.
 * </pre>
 */
public interface StockVarianceRepository extends JpaRepository<StockVariance, UUID>, JpaSpecificationExecutor<StockVariance> {

    @EntityGraph(attributePaths = {"count", "count.location", "count.product"})
    Optional<StockVariance> findDetailedById(UUID id);

    @EntityGraph(attributePaths = {"count"})
    Optional<StockVariance> findByCount_Id(UUID countId);

    /** A page of variances with their count and its place. */
    @Override
    @EntityGraph(attributePaths = {"count", "count.location"})
    org.springframework.data.domain.Page<StockVariance> findAll(org.springframework.data.jpa.domain.Specification<StockVariance> spec,
                                                                org.springframework.data.domain.Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from StockVariance v where v.id = :id")
    Optional<StockVariance> lockById(@Param("id") UUID id);

    /** Open variances whose due day has passed (escalated once). */
    @EntityGraph(attributePaths = {"count", "count.location"})
    List<StockVariance> findByStatusAndDueOnBefore(VarianceStatus status, LocalDate day);

    long countByStatusIn(Collection<VarianceStatus> statuses);

    /** Variances opened in a period (counts closed then), with their count. */
    @EntityGraph(attributePaths = {"count", "count.location"})
    List<StockVariance> findByCreatedAtBetweenOrderByCreatedAtAsc(LocalDateTime from, LocalDateTime to);

    /** Variances due in a period (variance resolution, IMP 19). */
    List<StockVariance> findByDueOnBetween(LocalDate from, LocalDate to);
}
