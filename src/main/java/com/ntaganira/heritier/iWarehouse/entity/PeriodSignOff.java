package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PeriodSignOff.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A closed month's accounts signed off (BP 4.2, Annex C): by the COO, then by the MD (their offices under
 *               the Delegation of Authority), before the Board. A sign-off counts for the month as closed when it was
 *               given: reopening the month leaves it on record but it no longer counts. Append-only
 *               (trg_period_sign_offs_append_only).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "period_sign_offs")
@Getter
@Setter
@NoArgsConstructor
public class PeriodSignOff {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "period_id", nullable = false)
    private UUID periodId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AuthorityLevel office;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;
}
