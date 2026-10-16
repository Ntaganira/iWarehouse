package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.ExpenseBill;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseBillStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : ExpenseBillRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Expense bills (CMP 01, 05): pages with their supplier, one with its lines, those waiting, those decided in a
 *               period, emergencies the Board has not ratified.
 * </pre>
 */
public interface ExpenseBillRepository extends JpaRepository<ExpenseBill, UUID>, JpaSpecificationExecutor<ExpenseBill> {

    @EntityGraph(attributePaths = {"supplier", "requisition", "lines", "lines.costCentre"})
    Optional<ExpenseBill> findDetailedById(UUID id);

    /** A page of bills with their supplier (no collection: paged in the database). */
    @Override
    @EntityGraph(attributePaths = {"supplier"})
    Page<ExpenseBill> findAll(Specification<ExpenseBill> spec, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ExpenseBill b where b.id = :id")
    Optional<ExpenseBill> lockById(@Param("id") UUID id);

    long countByStatusIn(Collection<ExpenseBillStatus> statuses);

    /** Bills decided in a period (the exceptions report). */
    @EntityGraph(attributePaths = {"supplier"})
    List<ExpenseBill> findByDecidedAtBetweenAndStatusInOrderByDecidedAtAsc(LocalDateTime from, LocalDateTime to,
                                                                           Collection<ExpenseBillStatus> statuses);

    /** Bills of a period by their day, with their supplier (the expenditure report, split purchases). */
    @EntityGraph(attributePaths = {"supplier"})
    List<ExpenseBill> findByBillDateBetweenAndStatusInOrderByBillDateAsc(LocalDate from, LocalDate to, Collection<ExpenseBillStatus> statuses);

    /** Emergencies approved and not ratified (CMP 05). */
    @EntityGraph(attributePaths = {"supplier"})
    List<ExpenseBill> findByEmergencyTrueAndStatusAndRatifiedAtIsNullOrderByDecidedAtAsc(ExpenseBillStatus status);

    /** The same supplier invoice billed already and not rejected or cancelled (CMP 09: duplicate claims). */
    @Query("select count(b) > 0 from ExpenseBill b where b.supplier.id = :supplierId and upper(b.supplierRef) = upper(:ref)"
            + " and b.status not in (com.ntaganira.heritier.iWarehouse.enums.ExpenseBillStatus.REJECTED,"
            + " com.ntaganira.heritier.iWarehouse.enums.ExpenseBillStatus.CANCELLED) and (:id is null or b.id <> :id)")
    boolean isBilled(@Param("supplierId") UUID supplierId, @Param("ref") String ref, @Param("id") UUID id);
}
