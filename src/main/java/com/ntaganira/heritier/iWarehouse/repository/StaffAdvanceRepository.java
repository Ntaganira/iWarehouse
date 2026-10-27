package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.StaffAdvance;
import com.ntaganira.heritier.iWarehouse.enums.StaffAdvanceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;
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
 * - File      : StaffAdvanceRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Staff advances (CMP 08): pages with their employee, one with its retirement lines, an employee's open ones, those
 *               overdue, those due or decided in a period.
 * </pre>
 */
public interface StaffAdvanceRepository extends JpaRepository<StaffAdvance, UUID>, JpaSpecificationExecutor<StaffAdvance> {

    @Override
    @EntityGraph(attributePaths = {"employee", "costCentre"})
    Page<StaffAdvance> findAll(Specification<StaffAdvance> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"employee", "employee.user", "costCentre", "payFund", "payFund.location", "settleFund", "settleFund.location", "lines", "lines.costCentre"})
    Optional<StaffAdvance> findDetailedById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StaffAdvance a where a.id = :id")
    Optional<StaffAdvance> lockById(@Param("id") UUID id);

    long countByStatus(StaffAdvanceStatus status);

    @EntityGraph(attributePaths = {"employee"})
    List<StaffAdvance> findByEmployee_IdAndStatusInOrderByRequestedAtAsc(UUID employeeId, Collection<StaffAdvanceStatus> statuses);

    /** Paid and not retired, their due day passed (CMP 08). */
    @EntityGraph(attributePaths = {"employee"})
    List<StaffAdvance> findByStatusAndDueOnBeforeOrderByDueOnAsc(StaffAdvanceStatus status, LocalDate day);

    /** Paid advances due in a period (the expenditure report's retirement rate). */
    List<StaffAdvance> findByDueOnBetweenAndStatusIn(LocalDate from, LocalDate to, Collection<StaffAdvanceStatus> statuses);

    /** Advances decided in a period (the exceptions report). */
    @EntityGraph(attributePaths = {"employee"})
    List<StaffAdvance> findByDecidedAtBetweenAndStatusInOrderByDecidedAtAsc(LocalDateTime from, LocalDateTime to,
                                                                            Collection<StaffAdvanceStatus> statuses);
}
