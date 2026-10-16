package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseCategory;
import com.ntaganira.heritier.iWarehouse.enums.PettyCashVoucherStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PettyCashVoucher.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A petty cash voucher (CMP 07, F-13 D; PCV-WH-2026-000001): a payment from a fund to a payee, its purpose,
 *               category and cost centre, at most the Settings' amount, recorded by the custodian with the original receipt
 *               and approved by another person (the head of department): approving posts it (Dr the category / Cr Petty
 *               Cash naming the site, F-16). Rejected or cancelled with a reason; a posted voucher is reversed, never edited.
 * </pre>
 */
@Entity
@Table(name = "petty_cash_vouchers")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class PettyCashVoucher extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fund_id", nullable = false, updatable = false)
    private PettyCashFund fund;

    @Column(name = "voucher_date", nullable = false)
    private LocalDate voucherDate;

    @Column(nullable = false, length = 100)
    private String payee;

    @Column(nullable = false, length = 255)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExpenseCategory category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cost_centre_id", nullable = false)
    private CostCentre costCentre;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /** The number on the shop's receipt. */
    @Column(name = "receipt_ref", length = 60)
    private String receiptRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PettyCashVoucherStatus status = PettyCashVoucherStatus.DRAFT;

    @Column(name = "recorded_by", nullable = false, length = 50, updatable = false)
    private String recordedBy;

    @Column(name = "recorded_by_id", updatable = false)
    private Long recordedById;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private LocalDateTime recordedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_by_id")
    private Long decidedById;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "journal_id")
    private UUID journalId;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "reversal_journal_id")
    private UUID reversalJournalId;

    @Column(name = "reversal_date")
    private LocalDate reversalDate;

    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;

    @Column(name = "reversed_by", length = 50)
    private String reversedBy;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    public boolean isDraft() {
        return status == PettyCashVoucherStatus.DRAFT;
    }

    public boolean isWaiting() {
        return status == PettyCashVoucherStatus.SUBMITTED;
    }

    public boolean isPosted() {
        return status == PettyCashVoucherStatus.APPROVED;
    }
}
