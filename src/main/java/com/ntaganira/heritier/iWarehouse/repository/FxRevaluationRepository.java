package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.FxRevaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : FxRevaluationRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Month-end revaluations of open foreign balances (ACC-08), one per month.
 * </pre>
 */
public interface FxRevaluationRepository extends JpaRepository<FxRevaluation, UUID> {

    boolean existsByPeriodEnd(LocalDate periodEnd);

    Optional<FxRevaluation> findByPeriodEnd(LocalDate periodEnd);

    /** The months revalued already (their last days). */
    @Query("select r.periodEnd from FxRevaluation r")
    List<LocalDate> findPeriodEnds();
}
