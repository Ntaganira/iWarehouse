package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AuditCaseStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : AuditCase.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The units of a trip missing or unexpected at the end of the day (FLT-10): opened when the trip is
 *               reconciled (AC-WH-2026-000001, one per trip), assigned to the warehouse supervisor, the owner told. Each
 *               unit is settled on its line; the case is resolved once every line is. Escalated to the owner with a note,
 *               it lets the trip close while its units are still to settle.
 * </pre>
 */
@Entity
@Table(name = "audit_cases")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class AuditCase extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, updatable = false)
    private Trip trip;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AuditCaseStatus status = AuditCaseStatus.OPEN;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private LocalDateTime openedAt;

    @Column(name = "missing_count", nullable = false, updatable = false)
    private int missingCount;

    @Column(name = "unexpected_count", nullable = false, updatable = false)
    private int unexpectedCount;

    /** The missing units' value (their own cost) when the case opened. */
    @Column(name = "missing_value", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal missingValue = BigDecimal.ZERO;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "escalated_by", length = 50)
    private String escalatedBy;

    @Column(name = "escalation_note", length = 255)
    private String escalationNote;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @OneToMany(mappedBy = "auditCase", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<AuditCaseLine> lines = new LinkedHashSet<>();

    public boolean isOpen() {
        return status == AuditCaseStatus.OPEN;
    }

    public boolean isEscalated() {
        return status == AuditCaseStatus.ESCALATED;
    }

    public boolean isResolved() {
        return status == AuditCaseStatus.RESOLVED;
    }

    /** Lets its trip close (FLT-10): resolved, or escalated to the owner. */
    public boolean isSettledForTrip() {
        return status != AuditCaseStatus.OPEN;
    }

    public long getOpenLineCount() {
        return lines.stream().filter(l -> !l.isSettled()).count();
    }
}
