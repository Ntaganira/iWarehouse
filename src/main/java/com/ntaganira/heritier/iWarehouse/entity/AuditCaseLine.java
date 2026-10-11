package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AuditLineKind;
import com.ntaganira.heritier.iWarehouse.enums.AuditLineOutcome;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : AuditCaseLine.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : One unit of an audit case (FLT-10): missing (expected back, not scanned; its own cost when the case
 *               opened) or unexpected (scanned, not expected). Settled once: a missing unit found, sold, written off or
 *               charged to the driver (FLT-11); an unexpected one explained.
 * </pre>
 */
@Entity
@Table(name = "audit_case_lines")
@AuditedEntity(ref = "unitCode")
@Getter
@Setter
@NoArgsConstructor
public class AuditCaseLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false, updatable = false)
    private AuditCase auditCase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private AuditLineKind kind;

    /** The unit; none for a label that is no unit's. */
    @Column(name = "stock_unit_id", updatable = false)
    private UUID stockUnitId;

    @Column(name = "unit_code", nullable = false, length = 30, updatable = false)
    private String unitCode;

    /** A missing unit's own cost when the case opened. */
    @Column(name = "unit_value", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal unitValue = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private AuditLineOutcome outcome;

    @Column(name = "outcome_at")
    private LocalDateTime outcomeAt;

    @Column(name = "outcome_by", length = 50)
    private String outcomeBy;

    @Column(length = 255)
    private String note;

    public boolean isMissing() {
        return kind == AuditLineKind.MISSING;
    }

    public boolean isSettled() {
        return outcome != null;
    }
}
