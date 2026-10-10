package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CreditNoteLine;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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

    /**
     * Rows of (credit note id, tax letter, VAT rate, amount VAT included) of the lines of the credit notes dated between two
     * days (the VAT report, TAX-05).
     */
    @Query("select l.creditNoteId, l.taxCode, l.vatRate, l.amount from CreditNoteLine l, CreditNote c"
            + " where c.id = l.creditNoteId and c.creditDate between :from and :to")
    List<Object[]> vatLines(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Lines of the credit notes of the invoices issued in a period as (credit note id, invoice line id, pieces, amount, kind of the
     * credit note), for the sales reports (RPT-05).
     */
    @Query("select l.creditNoteId, l.invoiceLineId, l.quantity, l.amount, cn.kind from CreditNoteLine l, CreditNote cn join cn.invoice i"
            + " where cn.id = l.creditNoteId and i.status = com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus.POSTED"
            + " and i.invoiceDate between :from and :to")
    List<Object[]> ofInvoicesIssuedIn(@Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);
}
