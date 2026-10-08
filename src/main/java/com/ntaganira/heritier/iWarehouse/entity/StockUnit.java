package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.UnitKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : StockUnit.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : One physical piece of glass (INV-01) with its label code (INV-03). Size, area and weight
 *               never change: a cut consumes the unit and creates new ones (SRS 6.2). Status, location
 *               and cost change only through StockService, which writes a stock movement for each move
 *               and a cost entry for each cost change (landed costs, PRC-05).
 * </pre>
 */
@Entity
@Table(name = "stock_units")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class StockUnit extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private UnitKind kind;

    /** Crate it came in (for cut pieces and off-cuts: the crate of the sheet they were cut from). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "crate_batch_id", updatable = false)
    private CrateBatch crateBatch;

    /** Unit it was cut from. */
    @Column(name = "parent_unit_id", updatable = false)
    private UUID parentUnitId;

    @Column(name = "width_mm", nullable = false, updatable = false)
    private int widthMm;

    @Column(name = "height_mm", nullable = false, updatable = false)
    private int heightMm;

    @Column(name = "area_m2", nullable = false, precision = 10, scale = 4, updatable = false)
    private BigDecimal areaM2;

    @Column(name = "weight_kg", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal weightKg;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private StockStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private Location location;

    /** Customer a RESERVED unit is held for (INV-05); none for pieces reserved before V13. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reserved_customer_id")
    private Customer reservedCustomer;

    @Column(name = "reserved_note", length = 255)
    private String reservedNote;

    /** RWF: the receipt cost plus landed costs added later (PRC-05); each change is a StockCostEntry. */
    @Column(name = "unit_cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal unitCost;

    /** RWF per m² of this unit, landed costs included, 4 decimals. */
    public BigDecimal getCostPerM2() {
        return unitCost.divide(areaM2, 4, RoundingMode.HALF_UP);
    }
}
