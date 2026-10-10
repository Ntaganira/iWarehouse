package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : SalesInvoiceRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Counter sales: the sale a till is ringing up, and issued invoices (POS-01, POS-04).
 * </pre>
 */
public interface SalesInvoiceRepository extends JpaRepository<SalesInvoice, UUID>, JpaSpecificationExecutor<SalesInvoice> {

    @EntityGraph(attributePaths = {"customer", "tillSession"})
    Page<SalesInvoice> findAll(Specification<SalesInvoice> spec, Pageable pageable);

    /** An invoice with its customer, till, lines and their glass. */
    @EntityGraph(attributePaths = {"customer", "customer.priceList", "tillSession", "lines", "lines.product", "lines.priceList",
            "lines.service", "lines.parentLine"})
    Optional<SalesInvoice> findDetailedById(UUID id);

    /**
     * The sale a till is ringing up, if any: one per till (uk_sales_invoices_draft), so no limit is needed. A limit with the
     * lines fetched would page in memory (HHH90003004) on every POS action.
     */
    @EntityGraph(attributePaths = {"customer", "customer.priceList", "tillSession", "lines", "lines.product", "lines.priceList",
            "lines.service", "lines.parentLine"})
    @Query("select i from SalesInvoice i where i.tillSession.id = :tillSessionId"
            + " and i.status = com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus.DRAFT")
    Optional<SalesInvoice> findDraftOfTill(@Param("tillSessionId") UUID tillSessionId);

    @EntityGraph(attributePaths = {"customer"})
    List<SalesInvoice> findByTillSession_IdAndStatusOrderByPostedAtAsc(UUID tillSessionId, SalesInvoiceStatus status);

    long countByTillSession_IdAndStatus(UUID tillSessionId, SalesInvoiceStatus status);

    /** The sale a quotation is being rung up into (POS-03), with its till. */
    @EntityGraph(attributePaths = {"tillSession"})
    Optional<SalesInvoice> findFirstByQuotationIdAndStatus(UUID quotationId, SalesInvoiceStatus status);

    /** Handing over the pieces of an invoice takes this lock first, so a piece is handed over once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from SalesInvoice i where i.id = :id")
    Optional<SalesInvoice> lockById(@Param("id") UUID id);

    /** Invoices issued in a period as (id, number, date, customer id, customer name, buyer name, posted by, net), for the sales reports (RPT-05). */
    @Query("select i.id, i.number, i.invoiceDate, c.id, c.name, i.buyerName, i.postedBy, i.netAmount from SalesInvoice i join i.customer c"
            + " where i.status = com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus.POSTED and i.invoiceDate between :from and :to")
    List<Object[]> issuedIn(@Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);

    /**
     * Invoices issued between two moments per channel, as (channel, invoices, net, total): COUNTER for a till's sale (the
     * vehicles' sales join with the mobile POS), for the owner dashboard (RPT-01).
     */
    @Query("select case when i.tillSession is null then 'OTHER' else 'COUNTER' end, count(i), coalesce(sum(i.netAmount), 0),"
            + " coalesce(sum(i.totalAmount), 0) from SalesInvoice i"
            + " where i.status = com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus.POSTED and i.postedAt between :from and :to"
            + " group by case when i.tillSession is null then 'OTHER' else 'COUNTER' end")
    List<Object[]> perChannel(@Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);
}
