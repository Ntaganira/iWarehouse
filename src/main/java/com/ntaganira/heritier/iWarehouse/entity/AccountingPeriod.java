package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.PeriodStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : AccountingPeriod.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A month closed (ACC-10), by its last day: who closed it and when; reopened, who, when and why. Months
 *               close in order and only the latest closed one reopens, so the closed months are always the first ones.
 * </pre>
 */
@Entity
@Table(name = "accounting_periods")
@AuditedEntity(ref = "periodEnd")
@Getter
@Setter
@NoArgsConstructor
public class AccountingPeriod extends BaseEntity {

    /** The month's last day. */
    @Column(name = "period_end", nullable = false, unique = true, updatable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PeriodStatus status;

    @Column(name = "closed_by", nullable = false, length = 50)
    private String closedBy;

    @Column(name = "closed_at", nullable = false)
    private LocalDateTime closedAt;

    @Column(name = "reopened_by", length = 50)
    private String reopenedBy;

    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    @Column(name = "reopen_reason", length = 255)
    private String reopenReason;

    public boolean isClosed() {
        return status == PeriodStatus.CLOSED;
    }
}
