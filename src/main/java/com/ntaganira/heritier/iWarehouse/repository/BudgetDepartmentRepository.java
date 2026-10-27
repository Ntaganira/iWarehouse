package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.BudgetDepartment;
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
 * - File      : BudgetDepartmentRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The departments' parts of a budget (CMP 04, T.01).
 * </pre>
 */
public interface BudgetDepartmentRepository extends JpaRepository<BudgetDepartment, UUID> {

    @EntityGraph(attributePaths = {"costCentre"})
    List<BudgetDepartment> findByBudgetIdOrderByCostCentre_CodeAsc(UUID budgetId);

    @EntityGraph(attributePaths = {"costCentre"})
    Optional<BudgetDepartment> findByBudgetIdAndCostCentre_Id(UUID budgetId, UUID costCentreId);
}
