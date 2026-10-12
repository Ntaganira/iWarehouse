package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.VehicleCost;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostPayment;
import com.ntaganira.heritier.iWarehouse.enums.VehicleCostStatus;
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

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : VehicleCostRepository.java
 * - Date      : 2026. 10. 12.
 * - User      : Hntaganira
 * - Desc      : Vehicle costs: the list (with their vehicle, trip and garage), one with everything its page shows, the
 *               costs of a trip or a vehicle, what drivers paid from a trip's cash (its float), the costs per vehicle
 *               and category of a period (the fleet report), and the highest odometer a vehicle's costs recorded.
 * </pre>
 */
public interface VehicleCostRepository extends JpaRepository<VehicleCost, UUID>, JpaSpecificationExecutor<VehicleCost> {

    @Override
    @EntityGraph(attributePaths = {"vehicle", "trip", "supplier"})
    Page<VehicleCost> findAll(Specification<VehicleCost> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"vehicle", "trip", "supplier", "driver", "driver.user", "servicePlan"})
    @Query("select c from VehicleCost c where c.id = :id")
    Optional<VehicleCost> findDetailedById(@Param("id") UUID id);

    /** Locks it while it is decided or reversed, so two people never act on it at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from VehicleCost c where c.id = :id")
    Optional<VehicleCost> lockById(@Param("id") UUID id);

    long countByStatus(VehicleCostStatus status);

    /** Costs waiting dated on or before a day: they stop the month's close (their journal would land in a closed month). */
    long countByStatusAndCostDateLessThanEqual(VehicleCostStatus status, LocalDate day);

    @EntityGraph(attributePaths = {"supplier"})
    List<VehicleCost> findByTrip_IdOrderByCostDateDescNumberDesc(UUID tripId);

    @EntityGraph(attributePaths = {"trip", "supplier"})
    Page<VehicleCost> findByVehicle_Id(UUID vehicleId, Pageable pageable);

    /** Rows of (trip id, amount) the drivers paid from some trips' cash, in some statuses (ACC-06). */
    @Query("select c.trip.id, coalesce(sum(c.amount), 0) from VehicleCost c where c.trip.id in :tripIds and c.payment = :payment "
            + "and c.status in :statuses group by c.trip.id")
    List<Object[]> paidFromFloat(@Param("tripIds") Collection<UUID> tripIds, @Param("payment") VehicleCostPayment payment,
                                 @Param("statuses") Collection<VehicleCostStatus> statuses);

    boolean existsByTrip_IdAndPaymentAndStatus(UUID tripId, VehicleCostPayment payment, VehicleCostStatus status);

    /** Rows of (vehicle id, category, net amount, litres) of the costs posted in a period. */
    @Query("select c.vehicle.id, c.category, coalesce(sum(c.amount - c.vatAmount), 0), coalesce(sum(c.litres), 0) from VehicleCost c "
            + "where c.status = :status and c.costDate between :from and :to group by c.vehicle.id, c.category")
    List<Object[]> totals(@Param("status") VehicleCostStatus status, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** The net amount of a vehicle's costs posted in a period. */
    @Query("select coalesce(sum(c.amount - c.vatAmount), 0) from VehicleCost c where c.vehicle.id = :vehicleId and c.status = :status "
            + "and c.costDate between :from and :to")
    java.math.BigDecimal totalOf(@Param("vehicleId") UUID vehicleId, @Param("status") VehicleCostStatus status,
                                 @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Rows of (vehicle id, highest odometer) that its costs recorded, waiting or posted. */
    @Query("select c.vehicle.id, max(c.odometerKm) from VehicleCost c where c.odometerKm is not null and c.status in :statuses group by c.vehicle.id")
    List<Object[]> highestOdometers(@Param("statuses") Collection<VehicleCostStatus> statuses);

    /** A vehicle's costs that recorded an odometer reading (its mileage log). */
    List<VehicleCost> findByVehicle_IdAndOdometerKmIsNotNullAndStatusIn(UUID vehicleId, Collection<VehicleCostStatus> statuses);
}
