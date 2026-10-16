package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityDecision;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseBillStatus;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseNature;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : ExpenseBill.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : An expense bill (CMP 01, 05; EXP-WH-2026-000001): a supplier’s invoice for utilities, services, fees,
 *               repairs, transport or a capital item, its lines by category, cost centre and location, its VAT, whether it
 *               is operating or capital, budgeted or not, an emergency; the requisition it uses. Recommended by the head of
 *               department, reviewed by Finance, approved within the expenditure matrix: approving posts it to the
 *               supplier’s account. An emergency approved by the Managing Director is ratified by the Board.
 * </pre>
 */
@Entity
@Table(name = "expense_bills")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class ExpenseBill extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "supplier_ref", nullable = false, length = 60)
    private String supplierRef;

    @Column(name = "bill_date", nullable = false)
    private LocalDate billDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(nullable = false, length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ExpenseNature nature = ExpenseNature.OPEX;

    @Column(nullable = false)
    private boolean budgeted = true;

    @Column(nullable = false)
    private boolean emergency;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requisition_id")
    private PurchaseRequisition requisition;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private AuthorityDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExpenseBillStatus status = ExpenseBillStatus.DRAFT;

    @Column(name = "entered_by", nullable = false, length = 50, updatable = false)
    private String enteredBy;

    @Column(name = "entered_by_id", updatable = false)
    private Long enteredById;

    @Column(name = "entered_at", nullable = false, updatable = false)
    private LocalDateTime enteredAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "recommended_by", length = 50)
    private String recommendedBy;

    @Column(name = "recommended_by_id")
    private Long recommendedById;

    @Column(name = "recommended_at")
    private LocalDateTime recommendedAt;

    @Column(name = "recommend_note", length = 500)
    private String recommendNote;

    @Column(name = "reviewed_by", length = 50)
    private String reviewedBy;

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_by_id")
    private Long decidedById;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "authority_level", length = 10)
    private AuthorityLevel authorityLevel;

    @Column(name = "journal_id")
    private UUID journalId;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "ratified_by", length = 50)
    private String ratifiedBy;

    @Column(name = "ratified_by_id")
    private Long ratifiedById;

    @Column(name = "ratified_at")
    private LocalDateTime ratifiedAt;

    @Column(name = "ratify_note", length = 500)
    private String ratifyNote;

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

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<ExpenseBillLine> lines = new ArrayList<>();

    public boolean isDraft() {
        return status == ExpenseBillStatus.DRAFT;
    }

    public boolean isWaiting() {
        return status.isWaiting();
    }

    public boolean isPosted() {
        return status == ExpenseBillStatus.APPROVED;
    }

    /** An emergency approved and not ratified by the Board yet. */
    public boolean isAwaitingRatification() {
        return emergency && status == ExpenseBillStatus.APPROVED && ratifiedAt == null;
    }
}
