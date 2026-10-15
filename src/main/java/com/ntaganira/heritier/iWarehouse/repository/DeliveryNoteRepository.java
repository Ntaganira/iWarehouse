package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.DeliveryNote;
import com.ntaganira.heritier.iWarehouse.enums.DeliveryNoteStatus;
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
 * - File      : DeliveryNoteRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Delivery notes (F-04): the list, one with its invoice and customer, those of an invoice,
 *               those waiting for release or for their signed copy, those released in a period.
 * </pre>
 */
public interface DeliveryNoteRepository extends JpaRepository<DeliveryNote, UUID>, JpaSpecificationExecutor<DeliveryNote> {

    @EntityGraph(attributePaths = {"invoice", "invoice.customer"})
    Page<DeliveryNote> findAll(Specification<DeliveryNote> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"invoice", "invoice.customer"})
    Optional<DeliveryNote> findDetailedById(UUID id);

    @EntityGraph(attributePaths = {"invoice"})
    List<DeliveryNote> findByInvoice_IdOrderByNumberAsc(UUID invoiceId);

    long countByStatus(DeliveryNoteStatus status);

    /** Released and their signed copy not recorded (overdue copies are reported). */
    @EntityGraph(attributePaths = {"invoice", "invoice.customer"})
    List<DeliveryNote> findByStatusAndCopyRecordedAtIsNullOrderByReleasedAtAsc(DeliveryNoteStatus status);

    /** Released before the EBM signature in a period (the exceptions report). */
    @EntityGraph(attributePaths = {"invoice", "invoice.customer"})
    List<DeliveryNote> findByEbmOverrideReasonIsNotNullAndReleasedAtBetweenOrderByReleasedAtAsc(LocalDateTime from, LocalDateTime to);

    /** Released in a period (delivery accuracy). */
    List<DeliveryNote> findByStatusAndReleasedAtBetween(DeliveryNoteStatus status, LocalDateTime from, LocalDateTime to);

    /** Releasing, recording the copy and cancelling take this lock, so a note moves once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from DeliveryNote n where n.id = :id")
    Optional<DeliveryNote> lockById(@Param("id") UUID id);
}
