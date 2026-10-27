package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.enums.WriteOffStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : ReceivableWriteOff.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A customer's debt written off (F-14): prepared by Credit Control with the reason and the recovery tried,
 *               reviewed by Finance (when Settings ask for it), approved by another person within the Delegation of
 *               Authority, which posts bad debts against the customer's receivable. Never more than they owe.
 * </pre>
 */
@Entity
@Table(name = "receivable_write_offs")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class ReceivableWriteOff extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    /** The invoice it is mainly about, if one. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", updatable = false)
    private SalesInvoice invoice;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "owed_when_asked", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal owedWhenAsked;

    @Column(name = "oldest_days", updatable = false)
    private Integer oldestDays;

    @Column(nullable = false, length = 255, updatable = false)
    private String reason;

    @Column(name = "recovery_action", length = 500, updatable = false)
    private String recoveryAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "authority_level", nullable = false, length = 10, updatable = false)
    private AuthorityLevel authorityLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WriteOffStatus status;

    @Column(name = "requested_by", nullable = false, length = 50, updatable = false)
    private String requestedBy;

    @Column(name = "requested_by_id", updatable = false)
    private Long requestedById;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

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

    @Column(name = "journal_id")
    private UUID journalId;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    public boolean isWaiting() {
        return status.isWaiting();
    }
}
