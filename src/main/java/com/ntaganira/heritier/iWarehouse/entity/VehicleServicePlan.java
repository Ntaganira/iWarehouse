package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : VehicleServicePlan.java
 * - Date      : 2026. 10. 12.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Work a vehicle needs regularly: an oil change every 5,000 km or 3 months, whichever comes first. When it
 *               was last done (and at how many km) moves forward when a posted vehicle cost does it; before it falls due
 *               the fleet is told (ServicePlans, ServicePlanService). Deactivated, never deleted.
 * </pre>
 */
@Entity
@Table(name = "vehicle_service_plans")
@AuditedEntity(ref = "name")
@Getter
@Setter
@NoArgsConstructor
public class VehicleServicePlan extends BaseEntity {

    /** Kept as an id: the vehicle page lists its plans without loading the vehicle again. */
    @Column(name = "vehicle_id", nullable = false, updatable = false)
    private UUID vehicleId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "every_km")
    private Integer everyKm;

    @Column(name = "every_months")
    private Integer everyMonths;

    @Column(name = "last_done_on", nullable = false)
    private LocalDate lastDoneOn;

    @Column(name = "last_done_km")
    private Integer lastDoneKm;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 255)
    private String note;
}
