package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.ntaganira.heritier.iWarehouse.enums.BudgetDepartmentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : BudgetDepartment.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A department's part of a budget (CMP 04, T.01): open while its head enters its lines, submitted by them (by the
 *               Settings' day, 30 November), sent back by Finance with a note.
 * </pre>
 */
@Entity
@Table(name = "budget_departments")
@AuditedEntity(ref = "status")
@Getter
@Setter
@NoArgsConstructor
public class BudgetDepartment extends BaseEntity {

    @Column(name = "budget_id", nullable = false, updatable = false)
    private UUID budgetId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cost_centre_id", nullable = false, updatable = false)
    private CostCentre costCentre;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BudgetDepartmentStatus status = BudgetDepartmentStatus.OPEN;

    @Column(name = "submitted_by", length = 50)
    private String submittedBy;

    @Column(name = "submitted_by_id")
    private Long submittedById;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "returned_by", length = 50)
    private String returnedBy;

    @Column(name = "returned_note", length = 500)
    private String returnedNote;

    public boolean isSubmitted() {
        return status == BudgetDepartmentStatus.SUBMITTED;
    }
}
