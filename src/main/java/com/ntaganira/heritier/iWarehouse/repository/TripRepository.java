package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.enums.TripStatus;
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
 * - File      : TripRepository.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Trips (FLT-05..07), and the units planned trips hold (INV-05).
 * </pre>
 */
public interface TripRepository extends JpaRepository<Trip, UUID>, JpaSpecificationExecutor<Trip> {

    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    Page<Trip> findAll(Specification<Trip> spec, Pageable pageable);

    /** A trip with its vehicle (and location), driver, manifest and fuel. */
    @EntityGraph(attributePaths = {"vehicle", "vehicle.location", "driver", "driver.user", "lines", "fuel"})
    Optional<Trip> findDetailedById(UUID id);

    /** Every change to a trip and its manifest takes this lock first, so it departs once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id")
    Optional<Trip> lockById(@Param("id") UUID id);

    /** Units planned trips hold: (unit id, trip number). */
    @Query(value = "SELECT l.stock_unit_id, t.number FROM trip_lines l JOIN trips t ON t.id = l.trip_id"
            + " WHERE t.status = 'PLANNED' AND l.stock_unit_id IN (:unitIds)", nativeQuery = true)
    List<Object[]> findHolds(@Param("unitIds") Collection<UUID> unitIds);

    /** (trip id, units planned, units loaded) of some trips, for a list. */
    @Query(value = "SELECT l.trip_id, COUNT(*), COUNT(l.loaded_at) FROM trip_lines l WHERE l.trip_id IN (:tripIds) GROUP BY l.trip_id",
            nativeQuery = true)
    List<Object[]> countLines(@Param("tripIds") Collection<UUID> tripIds);

    /** The trip a vehicle is on (one at a time, uk_trips_vehicle_on_road), with its driver for the vehicle's page. */
    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    Optional<Trip> findFirstByVehicle_IdAndStatus(UUID vehicleId, TripStatus status);

    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    Optional<Trip> findFirstByDriver_IdAndStatus(UUID driverId, TripStatus status);

    boolean existsByVehicle_IdAndStatusIn(UUID vehicleId, Collection<TripStatus> statuses);

    boolean existsByDriver_IdAndStatusIn(UUID driverId, Collection<TripStatus> statuses);

    boolean existsByVehicle_Id(UUID vehicleId);

    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    Page<Trip> findByVehicle_Id(UUID vehicleId, Pageable pageable);

    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    Page<Trip> findByDriver_Id(UUID driverId, Pageable pageable);

    long countByStatus(TripStatus status);
}
