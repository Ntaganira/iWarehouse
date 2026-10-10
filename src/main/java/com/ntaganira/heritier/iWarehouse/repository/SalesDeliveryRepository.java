package com.ntaganira.heritier.iWarehouse.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import com.ntaganira.heritier.iWarehouse.entity.SalesDelivery;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : SalesDeliveryRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Pieces of custom sizes handed over (SRS 5.3). Append-only: save and finders only.
 * </pre>
 */
public interface SalesDeliveryRepository extends Repository<SalesDelivery, UUID> {

    SalesDelivery save(SalesDelivery delivery);

    List<SalesDelivery> findByInvoiceIdOrderByDeliveredAtAscUnitCodeAsc(UUID invoiceId);

    /** Pieces handed over per invoice issued in a period, as (invoice id, pieces) (RPT-05). */
    @Query("select d.invoiceId, count(d) from SalesDelivery d, SalesInvoice i where i.id = d.invoiceId and i.invoiceDate between :from and :to"
            + " group by d.invoiceId")
    List<Object[]> handedOverPerInvoice(@Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);
}
