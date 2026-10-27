package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.DamageCause;
import com.ntaganira.heritier.iWarehouse.enums.DamageStatus;
import com.ntaganira.heritier.iWarehouse.enums.RecoveryFrom;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : DamageReport.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A damage report (F-07, DMG-009, IMP 13.3): where and when, the cause, what happened, who was there, whether it can be repaired, the units and their cost (the estimated loss). Reported, inspected by Dispatch, approved or rejected by the Director of Supply Chain (decision D5); while reported or inspected it holds its units. Approving writes units off on one adjustment or puts them back, and posts what someone pays back.
 * </pre>
 */
@Entity
@Table(name = "damage_reports")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class DamageReport extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DamageCause cause;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "people_present", length = 255)
    private String peoplePresent;

    @Column(nullable = false)
    private boolean repairable;

    @Column(name = "estimated_loss", nullable = false, precision = 18, scale = 2)
    private BigDecimal estimatedLoss = BigDecimal.ZERO;

    /** The credit note the units came back on, for a customer's return held for inspection (F-07 C). */
    @Column(name = "credit_note_id")
    private UUID creditNoteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DamageStatus status = DamageStatus.REPORTED;

    @Column(name = "reported_by", nullable = false, length = 50)
    private String reportedBy;

    @Column(name = "reported_by_id")
    private Long reportedById;

    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt;

    @Column(name = "inspected_by", length = 50)
    private String inspectedBy;

    @Column(name = "inspected_by_id")
    private Long inspectedById;

    @Column(name = "inspected_at")
    private LocalDateTime inspectedAt;

    @Column(name = "inspection_note", length = 500)
    private String inspectionNote;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_by_id")
    private Long decidedById;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "recover_from", nullable = false, length = 20)
    private RecoveryFrom recoverFrom = RecoveryFrom.NONE;

    @Column(name = "recover_party", length = 150)
    private String recoverParty;

    @Column(name = "recover_customer_id")
    private UUID recoverCustomerId;

    @Column(name = "recover_supplier_id")
    private UUID recoverSupplierId;

    @Column(name = "recover_driver_id")
    private UUID recoverDriverId;

    @Column(name = "recover_amount", precision = 18, scale = 2)
    private BigDecimal recoverAmount;

    @Column(name = "recovery_journal", length = 30)
    private String recoveryJournal;

    @Column(name = "adjustment_id")
    private UUID adjustmentId;

    @Column(name = "adjustment_number", length = 30)
    private String adjustmentNumber;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<DamageReportLine> lines = new ArrayList<>();

    /** Reported or inspected: its units are held until it is decided. */
    public boolean isOpen() {
        return status == DamageStatus.REPORTED || status == DamageStatus.INSPECTED;
    }

    public boolean isApproved() {
        return status == DamageStatus.APPROVED;
    }

    public boolean isRecoverable() {
        return recoverFrom != null && recoverFrom != RecoveryFrom.NONE;
    }

    /** The area of its units, m². */
    public BigDecimal getAreaM2() {
        return lines.stream().map(DamageReportLine::getAreaM2).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
