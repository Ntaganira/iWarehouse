package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CreditNoteUnit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : CreditNoteUnitRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Units brought back on credit notes (POS-09). Append-only: save and finders only.
 * </pre>
 */
public interface CreditNoteUnitRepository extends Repository<CreditNoteUnit, UUID> {

    CreditNoteUnit save(CreditNoteUnit unit);

    @EntityGraph(attributePaths = {"location"})
    List<CreditNoteUnit> findByCreditNoteIdOrderByUnitCode(UUID creditNoteId);

    /** Everything brought back against an invoice: each unit once. */
    List<CreditNoteUnit> findByInvoiceId(UUID invoiceId);
}
