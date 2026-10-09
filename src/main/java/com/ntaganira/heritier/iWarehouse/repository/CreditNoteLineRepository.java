package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CreditNoteLine;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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

    /** Pieces credited per invoice line, brought back or cancelled: rows of (line id, pieces). */
    @Query("select l.invoiceLineId, sum(l.quantity) from CreditNoteLine l where l.invoiceLineId in :lineIds group by l.invoiceLineId")
    List<Object[]> creditedPieces(@Param("lineIds") Collection<UUID> lineIds);

    /** Pieces of an order given up per invoice line (credit notes of kind CANCEL): rows of (line id, pieces). */
    @Query("select l.invoiceLineId, sum(l.quantity) from CreditNoteLine l where l.invoiceLineId in :lineIds and l.creditNoteId in"
            + " (select c.id from CreditNote c where c.kind = com.ntaganira.heritier.iWarehouse.enums.CreditNoteKind.CANCEL)"
            + " group by l.invoiceLineId")
    List<Object[]> cancelledPieces(@Param("lineIds") Collection<UUID> lineIds);
}
