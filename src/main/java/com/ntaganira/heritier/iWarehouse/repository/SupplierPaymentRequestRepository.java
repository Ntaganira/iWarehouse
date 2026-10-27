package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.SupplierPaymentRequest;
import com.ntaganira.heritier.iWarehouse.enums.PaymentRequestStatus;
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
 * - File      : SupplierPaymentRequestRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Supplier payments prepared (CMP 07): the list, one with its supplier, those waiting.
 * </pre>
 */
public interface SupplierPaymentRequestRepository extends JpaRepository<SupplierPaymentRequest, UUID>, JpaSpecificationExecutor<SupplierPaymentRequest> {

    @EntityGraph(attributePaths = {"supplier"})
    Page<SupplierPaymentRequest> findAll(Specification<SupplierPaymentRequest> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"supplier"})
    Optional<SupplierPaymentRequest> findDetailedById(UUID id);

    long countByStatusIn(List<PaymentRequestStatus> statuses);

    /** Deciding takes this lock, so a request is decided once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SupplierPaymentRequest r where r.id = :id")
    Optional<SupplierPaymentRequest> lockById(@Param("id") UUID id);

    /** Those decided in a period (the exceptions report). */
    @EntityGraph(attributePaths = {"supplier"})
    List<SupplierPaymentRequest> findByDecidedAtBetweenOrderByDecidedAtAsc(LocalDateTime from, LocalDateTime to);

    /** A supplier's requests still waiting: what they wait for is not paid twice. */
    List<SupplierPaymentRequest> findBySupplier_IdAndStatusIn(UUID supplierId, List<PaymentRequestStatus> statuses);
}
