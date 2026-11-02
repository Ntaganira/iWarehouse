package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AdministrativeTier;
import com.ntaganira.heritier.iWarehouse.enums.LocationType;
import com.ntaganira.heritier.iWarehouse.enums.RackOrientation;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Location.java
 * - Date      : 2026. 10. 07.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
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

    /** The administrative level of a site; null while it is not mapped. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tier", length = 10)
    private AdministrativeTier tier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "province_id")
    private Province province;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "district_id")
    private District district;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sector_id")
    private Sector sector;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cell_id")
    private Cell cell;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "village_id")
    private Village village;

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

    public boolean isSite() {
        return type == LocationType.SITE;
    }

    public boolean isVehicle() {
        return type == LocationType.VEHICLE;
    }

    /** The selected administrative unit of a site, or null while it is not mapped. */
    public AdministrativeNode getArea() {
        if (!isSite() || tier == null) {
            return null;
        }
        return switch (tier) {
            case PROVINCE -> province;
            case DISTRICT -> district;
            case SECTOR -> sector;
            case CELL -> cell;
            case VILLAGE -> village;
        };
    }

    public UUID getAreaId() {
        AdministrativeNode area = getArea();
        return area == null ? null : area.getId();
    }

    public String getAreaCode() {
        AdministrativeNode area = getArea();
        return area == null ? null : area.getCode();
    }

    public String getAreaName() {
        AdministrativeNode area = getArea();
        return area == null ? null : area.getName();
    }

    /** The full administrative path of a site, e.g. Kigali City / Gasabo / Kacyiru. */
    public String getAreaPath() {
        List<String> names = new ArrayList<>();
        if (province != null) {
            names.add(province.getName());
        }
        if (district != null) {
            names.add(district.getName());
        }
        if (sector != null) {
            names.add(sector.getName());
        }
        if (cell != null) {
            names.add(cell.getName());
        }
        if (village != null) {
            names.add(village.getName());
        }
        return names.isEmpty() ? null : String.join(" / ", names);
    }

    /** Racks and slots carry a label; sites and zones do not. */
    public boolean isLabelKind() {
        return type == LocationType.RACK || type == LocationType.SLOT;
    }

    public boolean isLabelled() {
        return labelPrintedAt != null;
    }
}
