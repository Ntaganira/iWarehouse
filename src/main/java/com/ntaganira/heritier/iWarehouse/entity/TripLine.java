package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : TripLine.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A unit on a trip's loading manifest (FLT-05): who planned it and when it was scanned onto the vehicle
 *               (FLT-06). While the trip is planned the line holds the unit and can be taken off; at departure it keeps
 *               the rack the unit left from.
 * </pre>
 */
@Entity
@Table(name = "trip_lines")
@AuditedEntity(ref = "unitCode")
@Getter
@Setter
@NoArgsConstructor
public class TripLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, updatable = false)
    private Trip trip;

    @Column(name = "stock_unit_id", nullable = false, updatable = false)
    private UUID stockUnitId;

    @Column(name = "unit_code", nullable = false, length = 30, updatable = false)
    private String unitCode;

    @Column(name = "planned_at", nullable = false, updatable = false)
    private LocalDateTime plannedAt;

    @Column(name = "planned_by", length = 50, updatable = false)
    private String plannedBy;

    /** Scanned onto the vehicle. */
    @Column(name = "loaded_at")
    private LocalDateTime loadedAt;

    @Column(name = "loaded_by", length = 50)
    private String loadedBy;

    /** The rack or slot it left from, at departure. */
    @Column(name = "from_location_id")
    private UUID fromLocationId;

    public boolean isLoaded() {
        return loadedAt != null;
    }
}
