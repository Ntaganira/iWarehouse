package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import com.ntaganira.heritier.iWarehouse.enums.RackOrientation;
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
 * - File      : Location.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : A place stock can be (MD-02): site, zone, rack, slot, or a vehicle's virtual location.
 *               Racks carry the MD-03 limits (kg, pieces, orientation) and may be reserved for off-cuts.
 *               Type and parent are fixed after creation. The parent is kept as an id: the whole tree
 *               is small and is always loaded at once. Deactivated, never deleted. Once a rack's or
 *               slot's label is printed its code is fixed (V15 trigger): the label carries it.
 * </pre>
 */
@Entity
@Table(name = "locations")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class Location extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 10, updatable = false)
    private LocationType type;

    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    /** Rack reserved for off-cuts (PRD-04). */
    @Column(nullable = false)
    private boolean offcut;

    @Column(name = "max_weight_kg")
    private Integer maxWeightKg;

    @Column(name = "max_pieces")
    private Integer maxPieces;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private RackOrientation orientation;

    @Column(nullable = false)
    private boolean enabled = true;

    /** First print of its label (racks and slots); the code is fixed from then on. */
    @Column(name = "label_printed_at")
    private LocalDateTime labelPrintedAt;

    @Column(name = "label_printed_by", length = 50)
    private String labelPrintedBy;

    public boolean isRack() {
        return type == LocationType.RACK;
    }

    /** Racks and slots carry a label; sites and zones do not. */
    public boolean isLabelKind() {
        return type == LocationType.RACK || type == LocationType.SLOT;
    }

    public boolean isLabelled() {
        return labelPrintedAt != null;
    }
}
