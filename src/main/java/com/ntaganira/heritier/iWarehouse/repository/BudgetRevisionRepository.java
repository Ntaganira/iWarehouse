package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.BudgetRevision;
import com.ntaganira.heritier.iWarehouse.enums.BudgetRevisionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : BudgetRevisionRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Budget revisions (CMP 04, T.02): a budget's, one with its line, one locked, those waiting, those decided.
 * </pre>
 */
public interface BudgetRevisionRepository extends JpaRepository<BudgetRevision, UUID> {

    @EntityGraph(attributePaths = {"line", "line.account", "line.costCentre"})
    Page<BudgetRevision> findByBudget_IdOrderByRequestedAtDesc(UUID budgetId, Pageable pageable);

    @EntityGraph(attributePaths = {"budget", "line", "line.account", "line.costCentre"})
    Optional<BudgetRevision> findDetailedById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BudgetRevision r where r.id = :id")
    Optional<BudgetRevision> lockById(@Param("id") UUID id);

    long countByStatus(BudgetRevisionStatus status);

    long countByBudget_IdAndStatus(UUID budgetId, BudgetRevisionStatus status);

    boolean existsByLine_IdAndStatus(UUID lineId, BudgetRevisionStatus status);

    @EntityGraph(attributePaths = {"budget", "line", "line.account", "line.costCentre"})
    List<BudgetRevision> findByDecidedAtBetweenAndStatusInOrderByDecidedAtAsc(LocalDateTime from, LocalDateTime to,
                                                                              Collection<BudgetRevisionStatus> statuses);
}
