package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Quotation;
import com.ntaganira.heritier.iWarehouse.enums.QuotationKind;
import com.ntaganira.heritier.iWarehouse.enums.QuotationStatus;
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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : QuotationRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Quotations (POS-03): the list, a quotation with its lines, the lock taken to change one.
 * </pre>
 */
public interface QuotationRepository extends JpaRepository<Quotation, UUID>, JpaSpecificationExecutor<Quotation> {

    @EntityGraph(attributePaths = {"customer"})
    Page<Quotation> findAll(Specification<Quotation> spec, Pageable pageable);

    /** A quotation with its customer, lines, their glass, processing and list. */
    @EntityGraph(attributePaths = {"customer", "customer.priceList", "lines", "lines.product", "lines.service", "lines.priceList",
            "lines.parentLine"})
    Optional<Quotation> findDetailedById(UUID id);

    /** An open order made from this quotation (one at a time). */
    boolean existsBySourceIdAndStatusIn(UUID sourceId, Collection<QuotationStatus> statuses);

    /** Orders sent to the cashier and not paid (the POS lists them; the dashboard counts them). */
    @EntityGraph(attributePaths = {"customer"})
    List<Quotation> findByKindAndStatusAndValidUntilGreaterThanEqualOrderByQuoteDateAscNumberAsc(QuotationKind kind, QuotationStatus status,
                                                                                               java.time.LocalDate day);

    long countByKindAndStatusAndValidUntilGreaterThanEqual(QuotationKind kind, QuotationStatus status, java.time.LocalDate day);

    /** Editing, sending, cancelling and ringing up take this lock first. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Quotation q where q.id = :id")
    Optional<Quotation> lockById(@Param("id") UUID id);
}
