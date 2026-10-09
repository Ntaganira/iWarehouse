package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SalesInvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
