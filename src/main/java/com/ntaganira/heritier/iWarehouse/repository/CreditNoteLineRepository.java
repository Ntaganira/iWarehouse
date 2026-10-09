package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CreditNoteLine;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : CreditNoteLineRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : What credit notes credit each invoice line (POS-09). Append-only: save and finders only.
 * </pre>
 */
public interface CreditNoteLineRepository extends Repository<CreditNoteLine, UUID> {

    CreditNoteLine save(CreditNoteLine line);

    List<CreditNoteLine> findByCreditNoteIdOrderByLineNo(UUID creditNoteId);

    List<CreditNoteLine> findByInvoiceLineIdIn(Collection<UUID> invoiceLineIds);
}
