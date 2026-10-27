package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.ntaganira.heritier.iWarehouse.enums.BudgetStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Budget.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A year's budget (CMP 04; BUD-WH-2026-000001): its lines by department and account (BudgetLine), the departments'
 *               submissions (BudgetDepartment), the year's landed cost and the demurrage ceiling per consignment.
 *               Prepared and consolidated by Finance, approved by the Board with its resolution, revised line by line
 *               within the Delegation of Authority (BudgetRevision).
 * </pre>
 */
@Entity
@Table(name = "budgets")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class Budget extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @Column(name = "budget_year", nullable = false, updatable = false)
    private int budgetYear;

    @Column(nullable = false, length = 150)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BudgetStatus status = BudgetStatus.DRAFT;

    /** The year's landed cost: freight, duty, clearing and the rest on imports (RWF, Annex D). */
    @Column(name = "landed_cost_budget", nullable = false, precision = 18, scale = 2)
    private BigDecimal landedCostBudget = BigDecimal.ZERO;

    /** Demurrage and storage one consignment may cost (RWF, Annex D, F-06). */
    @Column(name = "demurrage_ceiling", nullable = false, precision = 18, scale = 2)
    private BigDecimal demurrageCeiling = BigDecimal.ZERO;

    @Column(name = "prepared_by", nullable = false, length = 50, updatable = false)
    private String preparedBy;

    @Column(name = "prepared_by_id", updatable = false)
    private Long preparedById;

    @Column(name = "prepared_at", nullable = false, updatable = false)
    private LocalDateTime preparedAt;

    @Column(name = "submitted_by", length = 50)
    private String submittedBy;

    @Column(name = "submitted_by_id")
    private Long submittedById;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_by_id")
    private Long decidedById;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** The Board's resolution, or why it was cancelled. */
    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    /** Why the Board sent it back. */
    @Column(name = "returned_note", length = 500)
    private String returnedNote;

    public boolean isDraft() {
        return status == BudgetStatus.DRAFT;
    }

    public boolean isApproved() {
        return status == BudgetStatus.APPROVED;
    }
}
