package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.ntaganira.heritier.iWarehouse.enums.FixedAssetCategory;
import com.ntaganira.heritier.iWarehouse.enums.FixedAssetSource;
import com.ntaganira.heritier.iWarehouse.enums.FixedAssetStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : FixedAsset.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A fixed asset (C.08; FA-WH-00001): what it is, its category, cost centre and place, cost, residual value and useful
 *               life, depreciated straight line from a month (FixedAssets.monthly); from a capital item of an approved expense
 *               bill (its line), or registered at its value to date with the depreciation before the register. What it has
 *               been depreciated since is its depreciation lines, never a second balance. Disposed of with a reason: sold or
 *               scrapped, its book value out against what it fetched.
 * </pre>
 */
@Entity
@Table(name = "fixed_assets")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class FixedAsset extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private FixedAssetCategory category = FixedAssetCategory.OTHER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12, updatable = false)
    private FixedAssetSource source;

    @Column(name = "expense_bill_line_id", updatable = false)
    private UUID expenseBillLineId;

    @Column(name = "cost_centre_id")
    private UUID costCentreId;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "serial_number", length = 60)
    private String serialNumber;

    @Column(name = "acquired_on", nullable = false)
    private LocalDate acquiredOn;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal cost;

    @Column(name = "residual_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal residualValue = BigDecimal.ZERO;

    @Column(name = "useful_life_months", nullable = false)
    private int usefulLifeMonths;

    /** The first month depreciated (its first day). */
    @Column(name = "depreciate_from", nullable = false)
    private LocalDate depreciateFrom;

    /** Depreciation before the register (an asset registered at its value to date). */
    @Column(name = "opening_accumulated", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal openingAccumulated = BigDecimal.ZERO;

    /** Set once its opening journal is posted, in the same transaction (the row is written before the journal names it). */
    @Column(name = "opening_journal_id")
    private UUID openingJournalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FixedAssetStatus status = FixedAssetStatus.ACTIVE;

    @Column(name = "disposed_on")
    private LocalDate disposedOn;

    @Column(precision = 18, scale = 2)
    private BigDecimal proceeds;

    @Column(name = "proceeds_method", length = 15)
    private String proceedsMethod;

    @Column(name = "proceeds_reference", length = 60)
    private String proceedsReference;

    @Column(name = "disposal_reason", length = 500)
    private String disposalReason;

    @Column(name = "disposed_by", length = 50)
    private String disposedBy;

    @Column(name = "disposal_journal_id")
    private UUID disposalJournalId;

    public boolean isActive() {
        return status == FixedAssetStatus.ACTIVE;
    }

    /** What is depreciated over its life: cost less residual value. */
    public BigDecimal getDepreciable() {
        return cost.subtract(residualValue);
    }
}
