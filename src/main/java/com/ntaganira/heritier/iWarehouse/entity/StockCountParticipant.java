package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.CountRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : StockCountParticipant.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Someone who took part in a count (IMP 15.1, F-08, BP 11.2): the warehouse team, the Internal Controller, Finance, the external auditors; a user signs the closed count in the system, anyone else on the paper sheet (recorded by a user, the signed sheet kept as a document).
 * </pre>
 */
@Entity
@Table(name = "stock_count_participants")
@AuditedEntity(ref = "name")
@Getter
@Setter
@NoArgsConstructor
public class StockCountParticipant extends BaseEntity {

    @Column(name = "count_id", nullable = false, updatable = false)
    private UUID countId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CountRole role;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    @Column(name = "signed_by", length = 50)
    private String signedBy;

    @Column(name = "on_paper", nullable = false)
    private boolean onPaper;

    public boolean isSigned() {
        return signedAt != null;
    }
}
