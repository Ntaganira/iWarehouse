package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.AccountingPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : AccountingPeriodRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Closed months (ACC-10).
 * </pre>
 */
public interface AccountingPeriodRepository extends JpaRepository<AccountingPeriod, UUID> {

    /** The last day of the latest closed month: the books are closed through it (null when no month is). */
    @Query("select max(p.periodEnd) from AccountingPeriod p where p.status = com.ntaganira.heritier.iWarehouse.enums.PeriodStatus.CLOSED")
    LocalDate closedThrough();

    Optional<AccountingPeriod> findByPeriodEnd(LocalDate periodEnd);
}
