package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.VarianceCause;
import com.ntaganira.heritier.iWarehouse.enums.VarianceClass;
import com.ntaganira.heritier.iWarehouse.enums.VarianceStatus;
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
 * - File      : StockVariance.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A count variance followed up (IMP 16, VR-010): opened when a count closes with differences, with the units and value missing and found against the value counted, its class and whether it is above the tolerance; resolved with the root cause, the corrective action and the officer responsible within the Settings’ working days, escalated when late; material ones are told to the Board.
 * </pre>
 */
@Entity
@Table(name = "stock_variances")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class StockVariance extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "count_id", nullable = false, updatable = false)
    private StockCount count;

    @Column(name = "missing_units", nullable = false)
    private int missingUnits;

    @Column(name = "found_units", nullable = false)
    private int foundUnits;

    @Column(name = "misplaced_units", nullable = false)
    private int misplacedUnits;

    @Column(name = "unknown_units", nullable = false)
    private int unknownUnits;

    @Column(name = "missing_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal missingValue;

    @Column(name = "found_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal foundValue;

    @Column(name = "expected_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal expectedValue;

    @Column(name = "variance_percent", precision = 9, scale = 2)
    private BigDecimal variancePercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VarianceClass classification;

    @Column(name = "above_tolerance", nullable = false)
    private boolean aboveTolerance;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VarianceStatus status = VarianceStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "root_cause", length = 30)
    private VarianceCause rootCause;

    @Column(name = "corrective_action", length = 500)
    private String correctiveAction;

    @Column(name = "responsible_officer", length = 100)
    private String responsibleOfficer;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "resolved_by", length = 50)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "board_notified_at")
    private LocalDateTime boardNotifiedAt;

    public boolean isResolved() {
        return status == VarianceStatus.RESOLVED;
    }

    /** The net value: found less missing (negative when glass is missing). */
    public BigDecimal getNetValue() {
        return foundValue.subtract(missingValue);
    }

    public boolean isMaterial() {
        return classification == VarianceClass.MATERIAL;
    }
}
