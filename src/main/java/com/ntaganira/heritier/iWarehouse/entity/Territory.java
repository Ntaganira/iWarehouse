package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AdministrativeTier;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Territory.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A sales territory and its Regional Sales Manager (BP 6, Res. 7): customers and orders belong to one, so
 *               sales are reported by territory. Deactivated, never deleted.
 * </pre>
 */
@Entity
@Table(name = "territories")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class Territory extends BaseEntity {

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    /** The administrative level this territory covers. */
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

    /** The Regional Sales Manager, a user. */
    @Column(name = "manager_user_id")
    private Long managerUserId;

    @Column(name = "manager_name", length = 100)
    private String managerName;

    @Column(nullable = false)
    private boolean enabled = true;

    /** The selected unit, or null while a legacy territory is not mapped yet. */
    public AdministrativeNode getArea() {
        if (tier == null) {
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

    /** The full administrative path, e.g. Kigali City / Gasabo / Kacyiru. */
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
}
