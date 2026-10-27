package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : BudgetReview.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A month's budget against actual signed off (CMP 04, T.03: by the 10th), with how many lines were beyond the
 *               tolerance and the note that explains them. Never changed (append-only, trigger).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "budget_reviews")
@AuditedEntity(ref = "reviewMonth")
@Getter
@Setter
@NoArgsConstructor
public class BudgetReview extends BaseEntity {

    @Column(name = "review_year", nullable = false, updatable = false)
    private int reviewYear;

    @Column(name = "review_month", nullable = false, updatable = false)
    private int reviewMonth;

    @Column(nullable = false, updatable = false)
    private int flagged;

    @Column(name = "signed_by", nullable = false, length = 50, updatable = false)
    private String signedBy;

    @Column(name = "signed_by_id", updatable = false)
    private Long signedById;

    @Column(name = "signed_at", nullable = false, updatable = false)
    private LocalDateTime signedAt;

    @Column(length = 1000, updatable = false)
    private String note;
}
