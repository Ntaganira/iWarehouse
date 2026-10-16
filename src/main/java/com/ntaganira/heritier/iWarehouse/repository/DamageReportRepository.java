package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.DamageReport;
import com.ntaganira.heritier.iWarehouse.enums.DamageStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
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
 * - File      : DamageReportRepository.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : Damage reports (F-07): a page of them, one with its lines, the lock every decision takes, the units open reports hold, those of a period for the register and the exceptions report.
 * </pre>
 */
public interface DamageReportRepository extends JpaRepository<DamageReport, UUID>, JpaSpecificationExecutor<DamageReport> {

    @EntityGraph(attributePaths = {"location", "lines", "lines.product"})
    Optional<DamageReport> findDetailedById(UUID id);

    /** A page of reports with their place (no collection: paged in the database). */
    @Override
    @EntityGraph(attributePaths = {"location"})
    org.springframework.data.domain.Page<DamageReport> findAll(org.springframework.data.jpa.domain.Specification<DamageReport> spec,
                                                               org.springframework.data.domain.Pageable pageable);

    /** (report id, units) of some reports, for a list. */
    @Query("select l.report.id, count(l) from DamageReportLine l where l.report.id in :ids group by l.report.id")
    List<Object[]> countLines(@Param("ids") Collection<UUID> ids);

    /** Every decision on a report takes this lock first. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DamageReport d where d.id = :id")
    Optional<DamageReport> lockById(@Param("id") UUID id);

    /** Units reported or inspected reports hold: (unit id, report number). */
    @Query(value = "SELECT l.stock_unit_id, d.number FROM damage_report_lines l JOIN damage_reports d ON d.id = l.report_id"
            + " WHERE d.status IN ('REPORTED', 'INSPECTED') AND l.stock_unit_id IN (:unitIds)", nativeQuery = true)
    List<Object[]> findHolds(@Param("unitIds") Collection<UUID> unitIds);

    long countByStatusIn(Collection<DamageStatus> statuses);

    /** Reports of damage that happened in a period, with their lines (the damage register). */
    @EntityGraph(attributePaths = {"location", "lines", "lines.product"})
    List<DamageReport> findByOccurredOnBetweenAndStatusNotOrderByOccurredOnAscNumberAsc(LocalDate from, LocalDate to, DamageStatus status);

    /** Reports decided in a period (the exceptions report). */
    @EntityGraph(attributePaths = {"location"})
    List<DamageReport> findByDecidedAtBetweenAndStatusInOrderByDecidedAtAsc(LocalDateTime from, LocalDateTime to,
                                                                           Collection<DamageStatus> statuses);
}
