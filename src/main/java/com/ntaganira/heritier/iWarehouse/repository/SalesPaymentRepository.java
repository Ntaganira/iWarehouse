package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SalesPayment;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : SalesPaymentRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Payments of issued invoices (POS-04). Append-only: save and finders only.
 * </pre>
 */
public interface SalesPaymentRepository extends Repository<SalesPayment, UUID> {

    SalesPayment save(SalesPayment payment);

    List<SalesPayment> findByInvoiceIdOrderByLineNo(UUID invoiceId);

    /** Rows of (method, amount) taken by a till session's issued invoices. */
    @Query("select p.method, coalesce(sum(p.amount), 0) from SalesPayment p where p.invoiceId in"
            + " (select i.id from SalesInvoice i where i.tillSession.id = :sessionId"
            + " and i.status = com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus.POSTED) group by p.method")
    List<Object[]> totalsOfSession(@Param("sessionId") UUID sessionId);
}
