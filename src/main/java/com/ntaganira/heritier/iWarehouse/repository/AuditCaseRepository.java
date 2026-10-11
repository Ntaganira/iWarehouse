package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.AuditCase;
import com.ntaganira.heritier.iWarehouse.enums.AuditCaseStatus;
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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : AuditCaseRepository.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : Audit cases of trips back with units missing or unexpected (FLT-10).
 * </pre>
 */
public interface AuditCaseRepository extends JpaRepository<AuditCase, UUID>, JpaSpecificationExecutor<AuditCase> {

    @EntityGraph(attributePaths = {"trip", "trip.vehicle", "trip.driver", "trip.driver.user"})
    Page<AuditCase> findAll(Specification<AuditCase> spec, Pageable pageable);

    /** A case with its units and its trip (vehicle, driver). */
    @EntityGraph(attributePaths = {"lines", "trip", "trip.vehicle", "trip.vehicle.location", "trip.driver", "trip.driver.user"})
    Optional<AuditCase> findDetailedById(UUID id);

    /** The case of a trip (one per trip, uk_audit_cases_trip), with its units. */
    @EntityGraph(attributePaths = {"lines"})
    Optional<AuditCase> findByTrip_Id(UUID tripId);

    /** Settling a unit or escalating takes this lock first, so the case's status follows its lines. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AuditCase c where c.id = :id")
    Optional<AuditCase> lockById(@Param("id") UUID id);

    long countByStatus(AuditCaseStatus status);

    long countByStatusIn(Collection<AuditCaseStatus> statuses);

    /** Cases of some trips (the fleet reports), with their units. */
    @EntityGraph(attributePaths = {"lines"})
    List<AuditCase> findByTrip_IdIn(Collection<UUID> tripIds);
}
