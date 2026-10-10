package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SalesInvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : SalesInvoiceLineRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Lines of counter sales, and the units a sale being rung up holds (INV-05).
 * </pre>
 */
public interface SalesInvoiceLineRepository extends JpaRepository<SalesInvoiceLine, UUID> {

    /** Units in a sale being rung up: (unit id, the till's number). */
    @Query("select l.stockUnitId, l.invoice.tillSession.number from SalesInvoiceLine l"
            + " where l.invoice.status = com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus.DRAFT and l.stockUnitId in :unitIds")
    List<Object[]> findHolds(@Param("unitIds") Collection<UUID> unitIds);

    /**
     * Rows of (invoice id, tax letter, VAT rate, amount VAT included) of the lines of the invoices issued between two days
     * (the VAT report, TAX-05).
     */
    @Query("select l.invoice.id, l.taxCode, l.vatRate, l.amount from SalesInvoiceLine l"
            + " where l.invoice.status = com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus.POSTED"
            + " and l.invoice.invoiceDate between :from and :to")
    List<Object[]> vatLines(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Lines of the invoices issued in a period as (invoice id, line id, kind, product id, product code, service id, service name,
     * pieces, width, height, amount), for the sales reports (RPT-05).
     */
    @Query("select i.id, l.id, l.kind, p.id, p.code, s.id, s.name, l.quantity, l.widthMm, l.heightMm, l.amount from SalesInvoiceLine l"
            + " join l.invoice i join l.product p left join l.service s"
            + " where i.status = com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus.POSTED and i.invoiceDate between :from and :to")
    List<Object[]> issuedIn(@Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);
}
