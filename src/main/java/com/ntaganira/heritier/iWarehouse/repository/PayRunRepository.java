package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.PayRun;
import com.ntaganira.heritier.iWarehouse.enums.PayRunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : PayRunRepository.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : Pay runs (payroll). Changing one locks it first, so two people never post or pay it twice.
 * </pre>
 */
public interface PayRunRepository extends JpaRepository<PayRun, UUID>, JpaSpecificationExecutor<PayRun> {

    @EntityGraph(attributePaths = {"rateSet"})
    Optional<PayRun> findDetailedById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PayRun r where r.id = :id")
    Optional<PayRun> lockById(@Param("id") UUID id);

    /** A month has a run unless it was cancelled (uk_pay_runs_period). */
    boolean existsByPeriodAndStatusNot(LocalDate period, PayRunStatus status);

    long countByStatus(PayRunStatus status);

    boolean existsByRateSet_IdAndStatusIn(UUID rateSetId, Collection<PayRunStatus> statuses);

    /** The latest month with a run, to offer the next one. */
    Optional<PayRun> findFirstByStatusNotOrderByPeriodDesc(PayRunStatus status);

    /** The latest run in the given states: the payroll the cash-flow forecast expects each month (P.11). */
    Optional<PayRun> findFirstByStatusInOrderByPeriodDesc(Collection<PayRunStatus> statuses);

    Optional<PayRun> findFirstByPeriodAndStatusIn(LocalDate period, Collection<PayRunStatus> statuses);
}
