package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AdjustmentKind;
import com.ntaganira.heritier.iWarehouse.enums.UnitKind;
import com.ntaganira.heritier.iWarehouse.enums.WriteOffCause;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : StockAdjustmentLine.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : One change of an adjustment (INV-07): a unit written off (cause), a lost unit found (where),
 *               a new unit (product, kind, size, where) or a unit resized (new size). Its value is
 *               worked out again when the adjustment posts; a new unit or resize records the unit it made.
 * </pre>
 */
@Entity
@Table(name = "stock_adjustment_lines")
@AuditedEntity(ref = "lineNo")
@Getter
@Setter
@NoArgsConstructor
public class StockAdjustmentLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "adjustment_id", nullable = false, updatable = false)
    private StockAdjustment adjustment;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private AdjustmentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, updatable = false)
    private WriteOffCause cause;

    @Column(name = "stock_unit_id", updatable = false)
    private UUID stockUnitId;

    @Column(name = "unit_code", length = 30, updatable = false)
    private String unitCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", updatable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_kind", length = 10, updatable = false)
    private UnitKind unitKind;

    @Column(name = "width_mm", updatable = false)
    private Integer widthMm;

    @Column(name = "height_mm", updatable = false)
    private Integer heightMm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", updatable = false)
    private Location location;

    /** RWF the line changes the stock value by; final when posted. */
    @Column(name = "value_change", nullable = false, precision = 18, scale = 2)
    private BigDecimal valueChange = BigDecimal.ZERO;

    /** The unit a new unit or a resize created. */
    @Column(name = "result_unit_id")
    private UUID resultUnitId;
}
