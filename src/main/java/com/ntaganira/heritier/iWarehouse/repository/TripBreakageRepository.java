package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.TripBreakage;
import com.ntaganira.heritier.iWarehouse.enums.BreakageStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
 * - File      : TripBreakageRepository.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : Breakage reported on the road (FLT-08).
 * </pre>
 */
public interface TripBreakageRepository extends JpaRepository<TripBreakage, UUID> {

    Optional<TripBreakage> findByClientId(UUID clientId);

    /** A trip's reports, with the phone each came from. */
    @EntityGraph(attributePaths = {"device"})
    List<TripBreakage> findByTrip_IdOrderByHappenedAtAscUnitCodeAsc(UUID tripId);

    /** The report on a unit of a trip that still stands (reported or accepted: uk_trip_breakages_unit). */
    @Query("select b from TripBreakage b where b.trip.id = :tripId and b.stockUnitId = :unitId and b.status <> 'REJECTED'")
    Optional<TripBreakage> findStanding(@Param("tripId") UUID tripId, @Param("unitId") UUID unitId);

    /** Units of a trip reported broken and not rejected: a phone cannot sell them. */
    @Query("select b.stockUnitId from TripBreakage b where b.trip.id = :tripId and b.stockUnitId in :unitIds and b.status <> 'REJECTED'")
    List<UUID> findStandingUnitIds(@Param("tripId") UUID tripId, @Param("unitIds") Collection<UUID> unitIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from TripBreakage b where b.id = :id")
    Optional<TripBreakage> lockById(@Param("id") UUID id);

    long countByTrip_IdAndStatus(UUID tripId, BreakageStatus status);

    long countByStatus(BreakageStatus status);

    /** (driver id, units accepted broken) over trips back in a period, for the fleet reports. */
    @Query("select b.trip.driver.id, count(b) from TripBreakage b where b.status = 'ACCEPTED' and b.trip.id in :tripIds group by b.trip.driver.id")
    List<Object[]> countAcceptedByDriver(@Param("tripIds") Collection<UUID> tripIds);
}
