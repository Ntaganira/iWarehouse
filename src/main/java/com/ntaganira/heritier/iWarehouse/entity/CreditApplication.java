package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.enums.CreditApplicationKind;
import com.ntaganira.heritier.iWarehouse.enums.CreditApplicationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : CreditApplication.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Credit terms asked for a customer (F-02 C-D): the limit, payment terms and price list wanted beside the ones
 *               in force, why, and what the customer owed when asked. Credit Control assesses it, the Director of Finance
 *               reviews it (when Settings ask), another person approves it within the Delegation of Authority, which sets
 *               the terms on the customer and the date of the next review. What it asks is fixed once asked.
 * </pre>
 */
@Entity
@Table(name = "credit_applications")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class CreditApplication extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private CreditApplicationKind kind;

    @Column(name = "credit_limit", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal creditLimit;

    @Column(name = "payment_terms_days", nullable = false, updatable = false)
    private int paymentTermsDays;

    /** Null = the default price list. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "price_list_id", updatable = false)
    private PriceList priceList;

    @Column(name = "previous_limit", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal previousLimit;

    @Column(name = "previous_terms_days", nullable = false, updatable = false)
    private int previousTermsDays;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_price_list_id", updatable = false)
    private PriceList previousPriceList;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    @Column(name = "owed_when_asked", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal owedWhenAsked;

    /** Days its oldest overdue invoice was past due when asked; null = nothing overdue. */
    @Column(name = "oldest_overdue_days", updatable = false)
    private Integer oldestOverdueDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "authority_level", nullable = false, length = 10, updatable = false)
    private AuthorityLevel authorityLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CreditApplicationStatus status;

    @Column(name = "requested_by", nullable = false, length = 50, updatable = false)
    private String requestedBy;

    @Column(name = "requested_by_id", updatable = false)
    private Long requestedById;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "assessed_by", length = 50)
    private String assessedBy;

    @Column(name = "assessed_by_id")
    private Long assessedById;

    @Column(name = "assessed_at")
    private LocalDateTime assessedAt;

    @Column(name = "assessment_note", length = 500)
    private String assessmentNote;

    @Column(name = "reviewed_by", length = 50)
    private String reviewedBy;

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 255)
    private String reviewNote;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_by_id")
    private Long decidedById;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 255)
    private String decisionNote;

    public boolean isWaiting() {
        return status.isWaiting();
    }

    /** Whether the limit asked is above the one in force. */
    public boolean isIncrease() {
        return creditLimit.compareTo(previousLimit) > 0;
    }
}
