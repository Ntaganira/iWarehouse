package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.BankReconciliation;
import com.ntaganira.heritier.iWarehouse.enums.ReconciliationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : BankReconciliationRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Bank and mobile-money reconciliations (ACC-12), with their account.
 * </pre>
 */
public interface BankReconciliationRepository extends JpaRepository<BankReconciliation, UUID> {

    @EntityGraph(attributePaths = {"account"})
    Page<BankReconciliation> findAllBy(Pageable pageable);

    @EntityGraph(attributePaths = {"account"})
    Optional<BankReconciliation> findDetailedById(UUID id);

    /** The latest reconciliation in force of an account: its statement balance is where the next one starts. */
    Optional<BankReconciliation> findFirstByAccount_IdAndStatusOrderByStatementDateDesc(UUID accountId, ReconciliationStatus status);
}
