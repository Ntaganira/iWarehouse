package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : BudgetLine.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A line of a budget (CMP 04): a department (none for the company's lines, such as revenue) and an account, month by
 *               month (RWF). Changed while the budget is prepared; once approved, only by an approved revision.
 * </pre>
 */
@Entity
@Table(name = "budget_lines")
@AuditedEntity(ref = "total")
@Getter
@Setter
@NoArgsConstructor
public class BudgetLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "budget_id", nullable = false, updatable = false)
    private Budget budget;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cost_centre_id", updatable = false)
    private CostCentre costCentre;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(name = "m1", nullable = false, precision = 18, scale = 2)
    private BigDecimal m1 = BigDecimal.ZERO;

    @Column(name = "m2", nullable = false, precision = 18, scale = 2)
    private BigDecimal m2 = BigDecimal.ZERO;

    @Column(name = "m3", nullable = false, precision = 18, scale = 2)
    private BigDecimal m3 = BigDecimal.ZERO;

    @Column(name = "m4", nullable = false, precision = 18, scale = 2)
    private BigDecimal m4 = BigDecimal.ZERO;

    @Column(name = "m5", nullable = false, precision = 18, scale = 2)
    private BigDecimal m5 = BigDecimal.ZERO;

    @Column(name = "m6", nullable = false, precision = 18, scale = 2)
    private BigDecimal m6 = BigDecimal.ZERO;

    @Column(name = "m7", nullable = false, precision = 18, scale = 2)
    private BigDecimal m7 = BigDecimal.ZERO;

    @Column(name = "m8", nullable = false, precision = 18, scale = 2)
    private BigDecimal m8 = BigDecimal.ZERO;

    @Column(name = "m9", nullable = false, precision = 18, scale = 2)
    private BigDecimal m9 = BigDecimal.ZERO;

    @Column(name = "m10", nullable = false, precision = 18, scale = 2)
    private BigDecimal m10 = BigDecimal.ZERO;

    @Column(name = "m11", nullable = false, precision = 18, scale = 2)
    private BigDecimal m11 = BigDecimal.ZERO;

    @Column(name = "m12", nullable = false, precision = 18, scale = 2)
    private BigDecimal m12 = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    /** The amount of a month (1 to 12). */
    public BigDecimal month(int month) {
        return switch (month) {
            case 1 -> m1;
            case 2 -> m2;
            case 3 -> m3;
            case 4 -> m4;
            case 5 -> m5;
            case 6 -> m6;
            case 7 -> m7;
            case 8 -> m8;
            case 9 -> m9;
            case 10 -> m10;
            case 11 -> m11;
            case 12 -> m12;
            default -> throw new IllegalArgumentException("Month " + month);
        };
    }

    /** The twelve months, January first. */
    public BigDecimal[] getMonths() {
        return new BigDecimal[]{m1, m2, m3, m4, m5, m6, m7, m8, m9, m10, m11, m12};
    }

    /** Sets the twelve months and the total. */
    public void setMonths(BigDecimal[] values) {
        if (values.length != 12) {
            throw new IllegalArgumentException("Twelve months");
        }
        m1 = values[0];
        m2 = values[1];
        m3 = values[2];
        m4 = values[3];
        m5 = values[4];
        m6 = values[5];
        m7 = values[6];
        m8 = values[7];
        m9 = values[8];
        m10 = values[9];
        m11 = values[10];
        m12 = values[11];
        total = Arrays.stream(values).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
