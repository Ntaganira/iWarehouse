package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.TransactionTicket;
import com.ntaganira.heritier.iWarehouse.enums.TicketKind;
import com.ntaganira.heritier.iWarehouse.enums.TicketStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
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
 * - File      : TransactionTicketRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Transaction Tickets (F-05, Annex I): the list, one, those of a document, those open.
 * </pre>
 */
public interface TransactionTicketRepository extends JpaRepository<TransactionTicket, UUID>, JpaSpecificationExecutor<TransactionTicket> {

    boolean existsBySourceTypeAndSourceIdAndKind(String sourceType, UUID sourceId, TicketKind kind);

    Optional<TransactionTicket> findBySourceTypeAndSourceIdAndKind(String sourceType, UUID sourceId, TicketKind kind);

    /** A document's tickets (its page). */
    List<TransactionTicket> findBySourceIdOrderByRaisedAtAsc(UUID sourceId);

    long countByStatusIn(Collection<TicketStatus> statuses);

    long countByRaisedAtBetweenAndStatusIn(LocalDateTime from, LocalDateTime to, Collection<TicketStatus> statuses);

    /** Signing takes this lock, so a ticket moves one step at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransactionTicket t where t.id = :id")
    Optional<TransactionTicket> lockById(@Param("id") UUID id);
}
