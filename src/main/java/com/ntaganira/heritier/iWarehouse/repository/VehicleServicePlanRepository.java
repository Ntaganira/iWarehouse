package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.VehicleServicePlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : VehicleServicePlanRepository.java
 * - Date      : 2026. 10. 12.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Service plans: a vehicle's (active first, by name), every active one (the reminders), and whether a
 *               vehicle already has a plan of that name.
 * </pre>
 */
public interface VehicleServicePlanRepository extends JpaRepository<VehicleServicePlan, UUID> {

    List<VehicleServicePlan> findByVehicleIdOrderByEnabledDescNameAsc(UUID vehicleId);

    List<VehicleServicePlan> findByEnabledTrueOrderByName();

    List<VehicleServicePlan> findByVehicleIdAndEnabledTrueOrderByName(UUID vehicleId);

    boolean existsByVehicleIdAndNameIgnoreCase(UUID vehicleId, String name);

    boolean existsByVehicleIdAndNameIgnoreCaseAndIdNot(UUID vehicleId, String name, UUID id);
}
