package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Budget;
import com.ntaganira.heritier.iWarehouse.enums.BudgetStatus;
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
 * - File      : BudgetRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Budgets (CMP 04): all by year, a year's budget in force or approved, one locked, those waiting, those decided.
 * </pre>
 */
public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    List<Budget> findAllByOrderByBudgetYearDesc();

    /** The year's budget that is not cancelled (one at a time). */
    Optional<Budget> findFirstByBudgetYearAndStatusNot(int year, BudgetStatus status);

    Optional<Budget> findByBudgetYearAndStatus(int year, BudgetStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Budget b where b.id = :id")
    Optional<Budget> lockById(@Param("id") UUID id);

    long countByStatus(BudgetStatus status);

    List<Budget> findByDecidedAtBetweenAndStatusOrderByDecidedAtAsc(LocalDateTime from, LocalDateTime to, BudgetStatus status);

    @Query("select b from Budget b where lower(b.number) like :term or lower(b.title) like :term or str(b.budgetYear) like :term")
    Page<Budget> search(@Param("term") String term, Pageable pageable);
}
