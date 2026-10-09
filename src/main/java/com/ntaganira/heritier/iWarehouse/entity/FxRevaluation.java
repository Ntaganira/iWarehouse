package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : FxRevaluation.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A month's open foreign balances revalued at its last day's rate (ACC-08, unrealised FX): its
 *               journal is dated that day and reversed the next. One per month (uk_fx_revaluations_period).
 *               Posted when saved, never changed; its lines are FxRevaluationLine.
 * </pre>
 */
@Entity
@Table(name = "fx_revaluations")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class FxRevaluation extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    /** The month's last day: the revaluation journal's date. */
    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    /** The lines' total: positive a gain, negative a loss. */
    @Column(name = "gain_loss", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal gainLoss;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private LocalDateTime postedAt;

    @Column(name = "posted_by", nullable = false, length = 50, updatable = false)
    private String postedBy;
}
