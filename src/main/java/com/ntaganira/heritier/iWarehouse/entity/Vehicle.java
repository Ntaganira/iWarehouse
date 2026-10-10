package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditIgnore;
import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Vehicle.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A delivery vehicle that is a moving shop (FLT-01): plate, model, how its racks are set up, the most
 *               kg and pieces it carries (checked when a trip departs, FLT-06) and when its insurance and inspection
 *               expire (FLT-04). It is a stock location of its own (FLT-02): made with it, its code VEH-plate, never
 *               edited on the Locations screen. Deactivated, never deleted. The odometer is the last reading its trips
 *               recorded (FLT-12).
 * </pre>
 */
@Entity
@Table(name = "vehicles")
@AuditedEntity(ref = "plate")
@Getter
@Setter
@NoArgsConstructor
public class Vehicle extends BaseEntity {

    /** Upper case without spaces: RAC123A. */
    @Column(nullable = false, unique = true, length = 15)
    private String plate;

    @Column(nullable = false, length = 60)
    private String model;

    @Column(name = "rack_configuration", length = 255)
    private String rackConfiguration;

    @Column(name = "max_load_kg", nullable = false)
    private Integer maxLoadKg;

    @Column(name = "max_pieces", nullable = false)
    private Integer maxPieces;

    @Column(name = "insurance_expiry", nullable = false)
    private LocalDate insuranceExpiry;

    @Column(name = "inspection_expiry", nullable = false)
    private LocalDate inspectionExpiry;

    /** The last reading a trip recorded; the trips keep each one. */
    @AuditIgnore
    @Column(name = "odometer_km")
    private Integer odometerKm;

    /** Its VEHICLE location: where the units it carries are. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false, updatable = false)
    private Location location;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 255)
    private String notes;
}
