package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
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
 * - File      : MasterDataReview.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : A month's master data changes reviewed (Board Paper 11 and Table 9): customers, suppliers, glass and prices
 *               changed in it, how many, who reviewed them, when, and the note (duplicates or unsupported accounts found,
 *               or none). One a month.
 * </pre>
 */
@Entity
@Table(name = "master_data_reviews")
@AuditedEntity(ref = "periodStart")
@Getter
@Setter
@NoArgsConstructor
public class MasterDataReview extends BaseEntity {

    /** The month reviewed: its first day. */
    @Column(name = "period_start", nullable = false, unique = true, updatable = false)
    private LocalDate periodStart;

    @Column(nullable = false, updatable = false)
    private int changes;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "reviewed_by", nullable = false, length = 50, updatable = false)
    private String reviewedBy;

    @Column(name = "reviewed_by_id", updatable = false)
    private Long reviewedById;

    @Column(name = "reviewed_at", nullable = false, updatable = false)
    private LocalDateTime reviewedAt;
}
