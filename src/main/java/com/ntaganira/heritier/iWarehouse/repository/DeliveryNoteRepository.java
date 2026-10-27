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
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
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
    /** Notes planned for days (G.23), not cancelled, with their invoice and customer. */
    @EntityGraph(attributePaths = {"invoice", "invoice.customer"})
    List<DeliveryNote> findByScheduledOnBetweenAndStatusNotOrderByScheduledOnAscNumberAsc(java.time.LocalDate from, java.time.LocalDate to,
                                                                                         DeliveryNoteStatus status);

    /** The glass on notes by note: (note id, the sum of width x height x thickness in mm), for its weight. */
    @Query("select l.deliveryNoteId, coalesce(sum(l.widthMm * l.heightMm * p.thicknessMm), 0) from DeliveryNoteLine l, StockUnit u"
            + " join u.product p where u.id = l.stockUnitId and l.deliveryNoteId in :noteIds group by l.deliveryNoteId")
    List<Object[]> volumeByNote(@Param("noteIds") java.util.Collection<UUID> noteIds);

    /** Notes in a state, by the day they are planned for: the drafts Dispatch may move (G.23). */
    @EntityGraph(attributePaths = {"invoice", "invoice.customer"})
    List<DeliveryNote> findByStatusOrderByScheduledOnAscNumberAsc(DeliveryNoteStatus status);

    /** Pieces on notes by note: (note id, count). */
    @Query("select l.deliveryNoteId, count(l) from DeliveryNoteLine l where l.deliveryNoteId in :noteIds group by l.deliveryNoteId")
    List<Object[]> piecesByNote(@Param("noteIds") java.util.Collection<UUID> noteIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from DeliveryNote n where n.id = :id")
    Optional<DeliveryNote> lockById(@Param("id") UUID id);

    /** Invoices with a delivery note released since a moment, with when they were paid and their last release (order-to-delivery, Annex D). */
    @Query("select n.invoice.id, n.invoice.postedAt, max(n.releasedAt) from DeliveryNote n"
            + " where n.status = com.ntaganira.heritier.iWarehouse.enums.DeliveryNoteStatus.RELEASED and n.releasedAt >= :since"
            + " group by n.invoice.id, n.invoice.postedAt")
    List<Object[]> lastReleaseSince(@Param("since") LocalDateTime since);
}
