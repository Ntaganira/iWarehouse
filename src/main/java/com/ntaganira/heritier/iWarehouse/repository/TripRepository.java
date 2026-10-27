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

import java.time.LocalDateTime;
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
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Trips (FLT-05..07), and the units planned trips hold (INV-05).
 * </pre>
 */
public interface TripRepository extends JpaRepository<Trip, UUID>, JpaSpecificationExecutor<Trip> {

    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    Page<Trip> findAll(Specification<Trip> spec, Pageable pageable);

    /** A trip with its vehicle (and location), driver and manifest. */
    @EntityGraph(attributePaths = {"vehicle", "vehicle.location", "driver", "driver.user", "lines"})
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

    /** A vehicle's trips that recorded an odometer (its mileage log, the km it drove). */
    List<Trip> findByVehicle_IdAndOdometerStartIsNotNull(UUID vehicleId);

    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    Page<Trip> findByVehicle_Id(UUID vehicleId, Pageable pageable);

    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    Page<Trip> findByDriver_Id(UUID driverId, Pageable pageable);

    long countByStatus(TripStatus status);

    /** Planned trips whose load request waits for approval (F-09). */
    long countByStatusAndLoadApprovedAtIsNull(TripStatus status);

    /** Load requests approved in a period (the exceptions report). */
    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    List<Trip> findByLoadApprovedAtBetweenOrderByLoadApprovedAtAsc(LocalDateTime from, LocalDateTime to);

    /** Previous trips waived in a period (BP 7.3 rule 9; the exceptions report). */
    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    List<Trip> findByPreviousOverrideAtBetweenOrderByPreviousOverrideAtAsc(LocalDateTime from, LocalDateTime to);

    /** A driver's or a vehicle's trips in some states (the previous trip not cleared, BP 7.3 rule 9). */
    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    @Query("select t from Trip t where (t.driver.id = :driverId or t.vehicle.id = :vehicleId) and t.status in :statuses"
            + " order by t.departedAt desc")
    List<Trip> findOfDriverOrVehicle(@Param("driverId") UUID driverId, @Param("vehicleId") UUID vehicleId,
                                     @Param("statuses") Collection<TripStatus> statuses);

    /** Trips that left in a period, with their vehicles (the fleet report's KPIs, Annex D). */
    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    List<Trip> findByDepartedAtBetweenOrderByDepartedAtAsc(java.time.LocalDateTime from, java.time.LocalDateTime to);

    /** A vehicle's trips that left before a moment, the latest first (the trip before another). */
    @EntityGraph(attributePaths = {"vehicle"})
    List<Trip> findByVehicle_IdAndDepartedAtBeforeOrderByDepartedAtDesc(UUID vehicleId, java.time.LocalDateTime before);

    /** The driver's trip the phone still works for: on the road, or back and not closed (latest first). */
    @EntityGraph(attributePaths = {"vehicle", "vehicle.location", "driver", "driver.user"})
    List<Trip> findByDriver_IdAndStatusInOrderByDepartedAtDesc(UUID driverId, Collection<TripStatus> statuses);

    /** Trips back from the road whose float is not cleared yet (ACC-06), the oldest return first. */
    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    @Query("select t from Trip t where t.status in :statuses and not exists (select f.id from FloatClearance f where f.trip = t)"
            + " order by t.returnedAt, t.number")
    List<Trip> findNotCleared(@Param("statuses") Collection<TripStatus> statuses);

    /** Trips back from the road in a period, for the fleet reports. */
    @EntityGraph(attributePaths = {"vehicle", "driver", "driver.user"})
    @Query("select t from Trip t where t.status in :statuses and t.returnedAt >= :from and t.returnedAt < :to order by t.returnedAt, t.number")
    List<Trip> findReturnedBetween(@Param("statuses") Collection<TripStatus> statuses, @Param("from") java.time.LocalDateTime from,
                                   @Param("to") java.time.LocalDateTime to);

    /** Lines of the trips that left in a period: (outcome, trip status, count) (the monthly stock reconciliation). */
    @Query("select l.outcome, t.status, count(l) from TripLine l join l.trip t where t.departedAt between :from and :to group by l.outcome, t.status")
    List<Object[]> lineOutcomesDepartedBetween(@Param("from") java.time.LocalDateTime from, @Param("to") java.time.LocalDateTime to);
}
