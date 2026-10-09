package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CreditNote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : CreditNoteRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Credit notes (POS-09), with their invoice and customer; the cash a till refunded.
 * </pre>
 */
public interface CreditNoteRepository extends JpaRepository<CreditNote, UUID>, JpaSpecificationExecutor<CreditNote> {

    @EntityGraph(attributePaths = {"invoice", "customer"})
    Page<CreditNote> findAll(Specification<CreditNote> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"invoice", "invoice.tillSession", "customer"})
    Optional<CreditNote> findDetailedById(UUID id);

    List<CreditNote> findByInvoice_IdOrderByNumberAsc(UUID invoiceId);

    /** The cash refunds of a till session, in the order they were made. */
    @EntityGraph(attributePaths = {"invoice", "customer"})
    List<CreditNote> findByTillSessionIdOrderByPostedAtAsc(UUID tillSessionId);

    /** Cash a till session refunded: it left the drawer. */
    @Query("select coalesce(sum(c.refundAmount), 0) from CreditNote c where c.tillSessionId = :sessionId"
            + " and c.refundMethod = com.ntaganira.heritier.iWarehouse.enums.PaymentMethod.CASH")
    BigDecimal cashRefundsOfSession(@Param("sessionId") UUID sessionId);
}
