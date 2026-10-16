package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : InventoryKpiReview.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : The formal review of inventory accuracy (IMP 19): made by the Head of Inventory or the Managing
 *               Director once accuracy has stayed below target for the Settings' months, keyed by the last of those
 *               months, with the figures it was made on and its note. Never changed once made.
 * </pre>
 */
@Entity
@Table(name = "inventory_kpi_reviews")
@AuditedEntity(ref = "periodStart")
@Getter
@Setter
@NoArgsConstructor
public class InventoryKpiReview extends BaseEntity {

    /** The first day of the last month below target. */
    @Column(name = "period_start", nullable = false, unique = true, updatable = false)
    private LocalDate periodStart;

    @Column(name = "months_below", nullable = false, updatable = false)
    private int monthsBelow;

    @Column(precision = 6, scale = 2, updatable = false)
    private BigDecimal accuracy;

    @Column(nullable = false, precision = 6, scale = 2, updatable = false)
    private BigDecimal target;

    @Column(nullable = false, length = 1000, updatable = false)
    private String note;

    @Column(name = "reviewed_by", nullable = false, length = 50, updatable = false)
    private String reviewedBy;

    @Column(name = "reviewed_by_id", updatable = false)
    private Long reviewedById;

    @Column(name = "reviewed_at", nullable = false, updatable = false)
    private LocalDateTime reviewedAt;
}
