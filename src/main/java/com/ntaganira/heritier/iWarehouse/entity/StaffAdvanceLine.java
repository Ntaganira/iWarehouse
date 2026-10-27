package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : StaffAdvanceLine.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What a staff advance paid for, from its receipts (CMP 08): the category, what, the cost centre, the amount
 *               and the receipt's number. Written once, when the advance is retired (append-only, trigger).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "staff_advance_lines")
@AuditedEntity(ref = "description")
@Getter
@Setter
@NoArgsConstructor
public class StaffAdvanceLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "advance_id", nullable = false, updatable = false)
    private StaffAdvance advance;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private ExpenseCategory category;

    @Column(nullable = false, length = 255, updatable = false)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cost_centre_id", nullable = false, updatable = false)
    private CostCentre costCentre;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "receipt_ref", length = 60, updatable = false)
    private String receiptRef;
}
