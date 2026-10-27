package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.DamageOutcome;
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
 * - File      : DamageReportLine.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A unit on a damage report: its label, glass, place, area and cost when reported; what was decided for it (written off or back to stock).
 * </pre>
 */
@Entity
@Table(name = "damage_report_lines")
@AuditedEntity(ref = "unitCode")
@Getter
@Setter
@NoArgsConstructor
public class DamageReportLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private DamageReport report;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "stock_unit_id", nullable = false)
    private UUID stockUnitId;

    @Column(name = "unit_code", nullable = false, length = 30)
    private String unitCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "area_m2", nullable = false, precision = 10, scale = 4)
    private BigDecimal areaM2;

    @Column(name = "unit_cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal unitCost;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DamageOutcome outcome;
}
