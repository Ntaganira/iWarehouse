package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.ntaganira.heritier.iWarehouse.enums.DepreciationRunStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : DepreciationRun.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A month's depreciation (C.08, U.12; DEP-WH-2026-000001): each asset in use depreciated for the month
 *               (DepreciationLine), posted once; the latest run may be reversed with a reason.
 * </pre>
 */
@Entity
@Table(name = "depreciation_runs")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class DepreciationRun extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    /** The month's first day. */
    @Column(nullable = false, updatable = false)
    private LocalDate period;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal total;

    @Column(nullable = false, updatable = false)
    private int assets;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DepreciationRunStatus status = DepreciationRunStatus.POSTED;

    @Column(name = "run_by", nullable = false, length = 50, updatable = false)
    private String runBy;

    @Column(name = "run_at", nullable = false, updatable = false)
    private LocalDateTime runAt;

    @Column(name = "journal_id")
    private UUID journalId;

    @Column(name = "reversal_journal_id")
    private UUID reversalJournalId;

    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;

    @Column(name = "reversed_by", length = 50)
    private String reversedBy;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    public boolean isPosted() {
        return status == DepreciationRunStatus.POSTED;
    }
}
