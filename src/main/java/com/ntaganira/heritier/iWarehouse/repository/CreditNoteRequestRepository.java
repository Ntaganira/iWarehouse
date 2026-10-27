package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.CreditNoteRequest;
import com.ntaganira.heritier.iWarehouse.enums.CreditNoteRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : CreditNoteRequestRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Credit notes asked (F-14): the list, one with its invoice, customer and what it asks for, those waiting.
 * </pre>
 */
public interface CreditNoteRequestRepository extends JpaRepository<CreditNoteRequest, UUID>, JpaSpecificationExecutor<CreditNoteRequest> {

    @EntityGraph(attributePaths = {"invoice", "customer"})
    Page<CreditNoteRequest> findAll(Specification<CreditNoteRequest> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"invoice", "customer", "items", "sizes"})
    Optional<CreditNoteRequest> findDetailedById(UUID id);

    long countByStatusIn(List<CreditNoteRequestStatus> statuses);

    /** Deciding takes this lock, so a request is decided once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CreditNoteRequest r where r.id = :id")
    Optional<CreditNoteRequest> lockById(@Param("id") UUID id);

    /** Those decided in a period (the exceptions report). */
    @EntityGraph(attributePaths = {"invoice", "customer"})
    List<CreditNoteRequest> findByDecidedAtBetweenOrderByDecidedAtAsc(LocalDateTime from, LocalDateTime to);

    /** An invoice's requests still waiting or approved, so a unit is never asked back twice at once. */
    List<CreditNoteRequest> findByInvoice_IdAndStatusIn(UUID invoiceId, List<CreditNoteRequestStatus> statuses);
}
