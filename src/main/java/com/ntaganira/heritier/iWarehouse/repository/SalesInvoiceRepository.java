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
            "lines.service", "lines.parentLine", "trip", "trip.vehicle", "trip.driver", "trip.driver.user", "device"})
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

    /**
     * Invoices issued in a period as (id, number, date, customer id, customer name, buyer name, posted by, net, order channel,
     * channel, territory code, territory name), for the sales reports (RPT-05, G.19, G.20).
     */
    @Query("select i.id, i.number, i.invoiceDate, c.id, c.name, i.buyerName, i.postedBy, i.netAmount, i.orderChannel, i.channel, t.code, t.name"
            + " from SalesInvoice i join i.customer c left join Territory t on t.id = i.territoryId"
            + " where i.status = com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus.POSTED and i.invoiceDate between :from and :to")
    List<Object[]> issuedIn(@Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);

    /**
     * Invoices issued between two moments per channel, as (channel name, invoices, net, total): COUNTER for a till's sale,
     * MOBILE for a vehicle's, for the owner dashboard (RPT-01).
     */
    @Query("select str(i.channel), count(i), coalesce(sum(i.netAmount), 0), coalesce(sum(i.totalAmount), 0) from SalesInvoice i"
            + " where i.status = com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus.POSTED and i.postedAt between :from and :to"
            + " group by i.channel order by i.channel")
    List<Object[]> perChannel(@Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);

    /** Some invoices with their customers (lists of payments). */
    @EntityGraph(attributePaths = {"customer"})
    List<SalesInvoice> findByIdIn(java.util.Collection<UUID> ids);

    /** Invoices issued on a day with their customers (the daily close, F-12). */
    @EntityGraph(attributePaths = {"customer"})
    List<SalesInvoice> findByStatusAndInvoiceDateOrderByNumber(com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus status,
                                                              java.time.LocalDate invoiceDate);

    /** Invoices issued in a period with their customers (EBM against the ledger, U.13). */
    @EntityGraph(attributePaths = {"customer"})
    List<SalesInvoice> findByStatusAndInvoiceDateBetweenOrderByNumber(com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus status,
                                                                     java.time.LocalDate from, java.time.LocalDate to);

    /** A trip's issued sales with their customers and lines (its daily return, F-10). */
    @EntityGraph(attributePaths = {"customer", "lines"})
    List<SalesInvoice> findByTrip_IdAndStatusOrderByNumber(UUID tripId, com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus status);

    /** The mobile sale a phone sent under this UUID (SYNC-03): the same sale sent twice is taken once. */
    Optional<SalesInvoice> findByClientId(UUID clientId);

    /** A trip's mobile sales with their customer, by number. */
    @EntityGraph(attributePaths = {"customer", "device"})
    List<SalesInvoice> findByTrip_IdOrderByNumberAsc(UUID tripId);

    /** A mobile sale with its lines, trip, vehicle and driver, for the phone's receipt. */
    @EntityGraph(attributePaths = {"customer", "lines", "lines.product", "trip", "trip.vehicle", "trip.driver", "trip.driver.user", "device"})
    /** A customer's invoices in a status, newest first (a write-off may name one). */
    List<SalesInvoice> findByCustomer_IdAndStatusOrderByInvoiceDateDesc(UUID customerId, SalesInvoiceStatus status);

    /** Rows of (invoice id, extra days) of the invoices approved with extended terms (F-03): the ageing gives them longer. */
    @Query("select i.id, i.extraTermsDays from SalesInvoice i where i.extraTermsDays is not null")
    List<Object[]> extendedTerms();

    @Query("select i from SalesInvoice i where i.clientId = :clientId")
    Optional<SalesInvoice> findMobileByClientId(@Param("clientId") UUID clientId);

    /** Rows of (invoice id, salesperson, extra terms days) of these invoices: who sold what a customer owes (Q.05). */
    @Query("select i.id, coalesce(i.salesperson, i.createdBy), i.extraTermsDays from SalesInvoice i where i.id in :ids")
    List<Object[]> sellersOf(@Param("ids") java.util.Collection<UUID> ids);

    /** What the invoices issued in a period charged (VAT included): the discount analysis's base (R.06). */
    @Query("select coalesce(sum(i.totalAmount), 0) from SalesInvoice i"
            + " where i.status = com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus.POSTED and i.invoiceDate between :from and :to")
    java.math.BigDecimal issuedTotal(@Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);
}
