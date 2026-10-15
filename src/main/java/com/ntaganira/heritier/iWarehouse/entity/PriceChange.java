package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.enums.PriceChangeStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PriceChange.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : Prices of a price list changed, waiting for approval (SDP 5): its lines say each price from and to; another
 *               person approves it within the Delegation of Authority (the Managing Director by default), which applies
 *               them, or rejects it; whoever asked may withdraw it. A reason is kept.
 * </pre>
 */
@Entity
@Table(name = "price_changes")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class PriceChange extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_list_id", nullable = false, updatable = false)
    private PriceList priceList;

    @Column(nullable = false, length = 255, updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "authority_level", nullable = false, length = 10, updatable = false)
    private AuthorityLevel authorityLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PriceChangeStatus status;

    @Column(name = "requested_by", nullable = false, length = 50, updatable = false)
    private String requestedBy;

    @Column(name = "requested_by_id", updatable = false)
    private Long requestedById;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_by_id")
    private Long decidedById;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 255)
    private String decisionNote;

    public boolean isPending() {
        return status == PriceChangeStatus.PENDING;
    }
}
