package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.ReceivableWriteOff;
import com.ntaganira.heritier.iWarehouse.enums.WriteOffStatus;
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
 * - File      : ReceivableWriteOffRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Receivable write-offs (F-14): the list, one with its customer and invoice, those waiting.
 * </pre>
 */
public interface ReceivableWriteOffRepository extends JpaRepository<ReceivableWriteOff, UUID>, JpaSpecificationExecutor<ReceivableWriteOff> {

    @EntityGraph(attributePaths = {"customer", "invoice"})
    Page<ReceivableWriteOff> findAll(Specification<ReceivableWriteOff> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "invoice"})
    Optional<ReceivableWriteOff> findDetailedById(UUID id);

    long countByStatusIn(List<WriteOffStatus> statuses);

    /** Deciding takes this lock, so a request is decided once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReceivableWriteOff r where r.id = :id")
    Optional<ReceivableWriteOff> lockById(@Param("id") UUID id);

    /** Those decided in a period (the exceptions report). */
    @EntityGraph(attributePaths = {"customer", "invoice"})
    List<ReceivableWriteOff> findByDecidedAtBetweenOrderByDecidedAtAsc(LocalDateTime from, LocalDateTime to);

    /** A customer's write-offs, newest first (their account page). */
    List<ReceivableWriteOff> findByCustomer_IdOrderByRequestedAtDesc(UUID customerId);
}
