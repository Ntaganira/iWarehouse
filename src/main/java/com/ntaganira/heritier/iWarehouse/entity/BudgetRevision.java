package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.enums.BudgetRevisionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : BudgetRevision.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A revision of a line of an approved budget (CMP 04, T.02; BRV-WH-2026-000001): its new months, the totals before
 *               and after, the share it changes and the reason. Approved within the Delegation of Authority by that share
 *               (the MD up to 15 %, the Board above), which writes the line; rejected or cancelled with a reason.
 * </pre>
 */
@Entity
@Table(name = "budget_revisions")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class BudgetRevision extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "budget_id", nullable = false, updatable = false)
    private Budget budget;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "line_id", nullable = false, updatable = false)
    private BudgetLine line;

    @Column(name = "n1", nullable = false, precision = 18, scale = 2)
    private BigDecimal n1 = BigDecimal.ZERO;

    @Column(name = "n2", nullable = false, precision = 18, scale = 2)
    private BigDecimal n2 = BigDecimal.ZERO;

    @Column(name = "n3", nullable = false, precision = 18, scale = 2)
    private BigDecimal n3 = BigDecimal.ZERO;

    @Column(name = "n4", nullable = false, precision = 18, scale = 2)
    private BigDecimal n4 = BigDecimal.ZERO;

    @Column(name = "n5", nullable = false, precision = 18, scale = 2)
    private BigDecimal n5 = BigDecimal.ZERO;

    @Column(name = "n6", nullable = false, precision = 18, scale = 2)
    private BigDecimal n6 = BigDecimal.ZERO;

    @Column(name = "n7", nullable = false, precision = 18, scale = 2)
    private BigDecimal n7 = BigDecimal.ZERO;

    @Column(name = "n8", nullable = false, precision = 18, scale = 2)
    private BigDecimal n8 = BigDecimal.ZERO;

    @Column(name = "n9", nullable = false, precision = 18, scale = 2)
    private BigDecimal n9 = BigDecimal.ZERO;

    @Column(name = "n10", nullable = false, precision = 18, scale = 2)
    private BigDecimal n10 = BigDecimal.ZERO;

    @Column(name = "n11", nullable = false, precision = 18, scale = 2)
    private BigDecimal n11 = BigDecimal.ZERO;

    @Column(name = "n12", nullable = false, precision = 18, scale = 2)
    private BigDecimal n12 = BigDecimal.ZERO;

    @Column(name = "old_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal oldTotal;

    @Column(name = "new_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal newTotal;

    @Column(name = "change_percent", nullable = false, precision = 7, scale = 2)
    private BigDecimal changePercent;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BudgetRevisionStatus status = BudgetRevisionStatus.SUBMITTED;

    @Column(name = "requested_by", nullable = false, length = 50, updatable = false)
    private String requestedBy;

    @Column(name = "requested_by_id", updatable = false)
    private Long requestedById;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_by_id")
    private Long decidedById;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "authority_level", length = 10)
    private AuthorityLevel authorityLevel;

    public boolean isWaiting() {
        return status == BudgetRevisionStatus.SUBMITTED;
    }

    /** The amount of a month (1 to 12). */
    public BigDecimal month(int month) {
        return switch (month) {
            case 1 -> n1;
            case 2 -> n2;
            case 3 -> n3;
            case 4 -> n4;
            case 5 -> n5;
            case 6 -> n6;
            case 7 -> n7;
            case 8 -> n8;
            case 9 -> n9;
            case 10 -> n10;
            case 11 -> n11;
            case 12 -> n12;
            default -> throw new IllegalArgumentException("Month " + month);
        };
    }

    /** The twelve months, January first. */
    public BigDecimal[] getMonths() {
        return new BigDecimal[]{n1, n2, n3, n4, n5, n6, n7, n8, n9, n10, n11, n12};
    }

    /** Sets the twelve months. */
    public void setMonths(BigDecimal[] values) {
        if (values.length != 12) {
            throw new IllegalArgumentException("Twelve months");
        }
        n1 = values[0];
        n2 = values[1];
        n3 = values[2];
        n4 = values[3];
        n5 = values[4];
        n6 = values[5];
        n7 = values[6];
        n8 = values[7];
        n9 = values[8];
        n10 = values[9];
        n11 = values[10];
        n12 = values[11];
    }
}
