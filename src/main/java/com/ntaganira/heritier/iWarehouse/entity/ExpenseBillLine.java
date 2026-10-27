package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseCategory;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : ExpenseBillLine.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A line of an expense bill: its category (the account), what it is, the cost centre and location it is for,
 *               its amount before VAT and its VAT (F-15 A, T.05, T.06).
 * </pre>
 */
@Entity
@Table(name = "expense_bill_lines")
@AuditedEntity(ref = "description")
@Getter
@Setter
@NoArgsConstructor
public class ExpenseBillLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private ExpenseBill bill;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExpenseCategory category;

    @Column(nullable = false, length = 255)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cost_centre_id", nullable = false)
    private CostCentre costCentre;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "vat_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    public BigDecimal getAmount() {
        return netAmount.add(vatAmount);
    }
}
