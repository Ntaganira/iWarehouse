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
 * - File      : StockDayReview.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : A day's stock movements reviewed (IMP 12): how many there were, who reviewed them, when, and the note.
 *               One a day.
 * </pre>
 */
@Entity
@Table(name = "stock_day_reviews")
@AuditedEntity(ref = "day")
@Getter
@Setter
@NoArgsConstructor
public class StockDayReview extends BaseEntity {

    @Column(nullable = false, unique = true, updatable = false)
    private LocalDate day;

    @Column(nullable = false, updatable = false)
    private int movements;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "reviewed_by", nullable = false, length = 50, updatable = false)
    private String reviewedBy;

    @Column(name = "reviewed_by_id", updatable = false)
    private Long reviewedById;

    @Column(name = "reviewed_at", nullable = false, updatable = false)
    private LocalDateTime reviewedAt;
}
