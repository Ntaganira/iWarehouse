package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.BudgetLine;
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
 * - File      : BudgetLineRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Budget lines (CMP 04): a budget's lines with their department and account, one by department and account.
 * </pre>
 */
public interface BudgetLineRepository extends JpaRepository<BudgetLine, UUID> {

    @EntityGraph(attributePaths = {"costCentre", "account"})
    List<BudgetLine> findByBudget_Id(UUID budgetId);

    @EntityGraph(attributePaths = {"costCentre", "account", "budget"})
    Optional<BudgetLine> findDetailedById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from BudgetLine l where l.id = :id")
    Optional<BudgetLine> lockById(@Param("id") UUID id);
}
