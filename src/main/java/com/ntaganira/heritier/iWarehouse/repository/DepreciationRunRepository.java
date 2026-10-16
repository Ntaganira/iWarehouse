package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.DepreciationRun;
import com.ntaganira.heritier.iWarehouse.enums.DepreciationRunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : DepreciationRunRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Depreciation runs (C.08): pages, one locked, the latest posted, a month's posted run.
 * </pre>
 */
public interface DepreciationRunRepository extends JpaRepository<DepreciationRun, UUID> {

    Page<DepreciationRun> findAllByOrderByPeriodDescRunAtDesc(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DepreciationRun r where r.id = :id")
    Optional<DepreciationRun> lockById(@Param("id") UUID id);

    Optional<DepreciationRun> findFirstByStatusOrderByPeriodDesc(DepreciationRunStatus status);

    Optional<DepreciationRun> findFirstByStatusOrderByPeriodAsc(DepreciationRunStatus status);

    Optional<DepreciationRun> findByPeriodAndStatus(LocalDate period, DepreciationRunStatus status);
}
