package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
    @EntityGraph(attributePaths = {"customer", "customer.priceList", "tillSession", "lines", "lines.product", "lines.priceList"})
    Optional<SalesInvoice> findDetailedById(UUID id);

    /** The sale a till is ringing up, if any. */
    @EntityGraph(attributePaths = {"customer", "customer.priceList", "tillSession", "lines", "lines.product", "lines.priceList"})
    Optional<SalesInvoice> findFirstByTillSession_IdAndStatus(UUID tillSessionId, SalesInvoiceStatus status);

    @EntityGraph(attributePaths = {"customer"})
    List<SalesInvoice> findByTillSession_IdAndStatusOrderByPostedAtAsc(UUID tillSessionId, SalesInvoiceStatus status);

    long countByTillSession_IdAndStatus(UUID tillSessionId, SalesInvoiceStatus status);
}
