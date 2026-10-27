package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SalesPayment;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : SalesPaymentRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Payments of issued invoices (POS-04). Append-only: save and finders only.
 * </pre>
 */
public interface SalesPaymentRepository extends Repository<SalesPayment, UUID> {

    SalesPayment save(SalesPayment payment);

    Optional<SalesPayment> findById(UUID id);

    List<SalesPayment> findByInvoiceIdOrderByLineNo(UUID invoiceId);

    /** The payments of some invoices (the daily close: what each was paid with at issue). */
    List<SalesPayment> findByInvoiceIdIn(Collection<UUID> invoiceIds);

    /** Balances of orders paid between two moments (the daily close's collections). */
    List<SalesPayment> findByBalancePaymentTrueAndCreatedAtBetweenOrderByCreatedAtAsc(LocalDateTime from, LocalDateTime to);

    /** Payments by one method made since a moment, oldest first (bank transfers to confirm, SDP 14). */
    List<SalesPayment> findByMethodAndCreatedAtAfterOrderByCreatedAtAsc(PaymentMethod method, LocalDateTime since);

    List<SalesPayment> findByInvoiceIdAndMethod(UUID invoiceId, PaymentMethod method);

    /** Rows of (method, amount) a till session took: its sales and the balances paid in it (POS-08). */
    @Query("select p.method, coalesce(sum(p.amount), 0) from SalesPayment p where p.tillSessionId = :sessionId group by p.method")
    List<Object[]> totalsOfSession(@Param("sessionId") UUID sessionId);

    /** Balances of orders paid in a till session, in the order they were taken. */
    List<SalesPayment> findByTillSessionIdAndBalancePaymentTrueOrderByCreatedAtAscLineNoAsc(UUID tillSessionId);

    /** Rows of (trip id, amount) taken by one method on some trips: the cash their sales put on the driver's float (ACC-06). */
    @Query("select p.tripId, coalesce(sum(p.amount), 0) from SalesPayment p where p.tripId in :tripIds and p.method = :method group by p.tripId")
    List<Object[]> totalsOfTrips(@Param("tripIds") Collection<UUID> tripIds, @Param("method") PaymentMethod method);

    /** Rows of (trip id, method, amount) of some trips, for the fleet reports. */
    @Query("select p.tripId, p.method, coalesce(sum(p.amount), 0) from SalesPayment p where p.tripId in :tripIds group by p.tripId, p.method")
    List<Object[]> totalsByMethodOfTrips(@Param("tripIds") Collection<UUID> tripIds);
}
