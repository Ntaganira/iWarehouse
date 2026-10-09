package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SaleApproval;
import com.ntaganira.heritier.iWarehouse.enums.SaleApprovalStatus;
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
 * - File      : SaleApprovalRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Approval requests at the counter (POS-05, POS-06): those of a sale, the pending ones, the list.
 * </pre>
 */
public interface SaleApprovalRepository extends JpaRepository<SaleApproval, UUID>, JpaSpecificationExecutor<SaleApproval> {

    @EntityGraph(attributePaths = {"customer", "invoice", "invoice.tillSession"})
    Page<SaleApproval> findAll(Specification<SaleApproval> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "invoice", "invoice.tillSession"})
    Optional<SaleApproval> findDetailedById(UUID id);

    /** A sale's requests, oldest first (numbers follow the order they were asked in). */
    List<SaleApproval> findByInvoice_IdOrderByNumberAsc(UUID invoiceId);

    List<SaleApproval> findByInvoice_IdAndStatusIn(UUID invoiceId, List<SaleApprovalStatus> statuses);

    long countByStatus(SaleApprovalStatus status);

    /** The till of a request's sale, read without loading the request (approving locks the till first). */
    @Query("select a.invoice.tillSession.id from SaleApproval a where a.id = :id")
    Optional<UUID> tillOf(@Param("id") UUID id);

    /** Approving, rejecting and withdrawing take this lock, so a request is decided once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from SaleApproval a where a.id = :id")
    Optional<SaleApproval> lockById(@Param("id") UUID id);
}
