package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.BankReconciliationLine;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : BankReconciliationLineRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : The ledger lines reconciliations cleared (ACC-12). Append-only: save and finders only.
 * </pre>
 */
public interface BankReconciliationLineRepository extends Repository<BankReconciliationLine, UUID> {

    BankReconciliationLine save(BankReconciliationLine line);

    @EntityGraph(attributePaths = {"journalLine", "journalLine.entry"})
    List<BankReconciliationLine> findByReconciliationId(UUID reconciliationId);

    /** The lines of an account cleared by a reconciliation in force. */
    @Query("select l.journalLine.id from BankReconciliationLine l, BankReconciliation r where r.id = l.reconciliationId"
            + " and r.status = com.ntaganira.heritier.iWarehouse.enums.ReconciliationStatus.RECONCILED and r.account.id = :accountId")
    Set<UUID> clearedLineIds(@Param("accountId") UUID accountId);
}
