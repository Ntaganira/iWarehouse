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
 * - File      : StockReconciliation.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : A month's stock reconciliation signed off (BP 7.4, Table 9, Annex I step 5): units loaded on vehicles
 *               against those sold, back, broken, lost or still on board; units sent to other sites against those arrived
 *               or in transit; tickets not posted; stock lost or found at cost against the stock value and whether it
 *               passed the Settings' tolerance; who reviewed it and the note. One a month.
 * </pre>
 */
@Entity
@Table(name = "stock_reconciliations")
@AuditedEntity(ref = "periodStart")
@Getter
@Setter
@NoArgsConstructor
public class StockReconciliation extends BaseEntity {

    @Column(name = "period_start", nullable = false, unique = true, updatable = false)
    private LocalDate periodStart;

    @Column(name = "loaded_units", nullable = false, updatable = false)
    private int loadedUnits;

    @Column(name = "accounted_units", nullable = false, updatable = false)
    private int accountedUnits;

    @Column(name = "dispatched_units", nullable = false, updatable = false)
    private int dispatchedUnits;

    @Column(name = "arrived_units", nullable = false, updatable = false)
    private int arrivedUnits;

    @Column(name = "tickets_open", nullable = false, updatable = false)
    private int ticketsOpen;

    @Column(name = "variance_value", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal varianceValue;

    @Column(name = "stock_value", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal stockValue;

    @Column(name = "variance_percent", nullable = false, precision = 8, scale = 4, updatable = false)
    private BigDecimal variancePercent;

    @Column(name = "above_tolerance", nullable = false, updatable = false)
    private boolean aboveTolerance;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "reviewed_by", nullable = false, length = 50, updatable = false)
    private String reviewedBy;

    @Column(name = "reviewed_by_id", updatable = false)
    private Long reviewedById;

    @Column(name = "reviewed_at", nullable = false, updatable = false)
    private LocalDateTime reviewedAt;
}
