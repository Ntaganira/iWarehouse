package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.PurchaseRequisition;
import com.ntaganira.heritier.iWarehouse.enums.RequisitionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
 * - File      : PurchaseRequisitionRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Purchase requisitions (CMP 06): pages with their department, one with its lines and quotes, the approved ones an
 *               order or a bill may use.
 * </pre>
 */
public interface PurchaseRequisitionRepository extends JpaRepository<PurchaseRequisition, UUID>, JpaSpecificationExecutor<PurchaseRequisition> {

    @EntityGraph(attributePaths = {"costCentre", "lines", "quotes", "quotes.supplier"})
    Optional<PurchaseRequisition> findDetailedById(UUID id);

    /** A page of requisitions with their department (no collection: paged in the database). */
    @Override
    @EntityGraph(attributePaths = {"costCentre"})
    Page<PurchaseRequisition> findAll(Specification<PurchaseRequisition> spec, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PurchaseRequisition r where r.id = :id")
    Optional<PurchaseRequisition> lockById(@Param("id") UUID id);

    /** Approved requisitions not used yet, with their quotes (an order or a bill picks one). */
    @EntityGraph(attributePaths = {"costCentre", "quotes", "quotes.supplier"})
    List<PurchaseRequisition> findByStatusOrderByNumberAsc(RequisitionStatus status);

    long countByStatus(RequisitionStatus status);

    /** Requisitions decided in a period (the exceptions report). */
    @EntityGraph(attributePaths = {"costCentre"})
    List<PurchaseRequisition> findByDecidedAtBetweenAndStatusInOrderByDecidedAtAsc(LocalDateTime from, LocalDateTime to,
                                                                                   Collection<RequisitionStatus> statuses);
}
