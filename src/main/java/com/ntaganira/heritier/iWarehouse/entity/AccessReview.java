package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : AccessReview.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A quarterly review of who holds which rights (Board Paper Table 8 and Table 9): who reviewed, when, how
 *               many users and enabled users there were, and what was checked and changed. Recorded once, never changed.
 * </pre>
 */
@Entity
@Table(name = "access_reviews")
@AuditedEntity(ref = "reviewedBy")
@Getter
@Setter
@NoArgsConstructor
public class AccessReview extends BaseEntity {

    @Column(name = "reviewed_at", nullable = false, updatable = false)
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by", nullable = false, length = 50, updatable = false)
    private String reviewedBy;

    @Column(name = "users_count", nullable = false, updatable = false)
    private int usersCount;

    @Column(name = "enabled_count", nullable = false, updatable = false)
    private int enabledCount;

    @Column(nullable = false, length = 500, updatable = false)
    private String note;
}
