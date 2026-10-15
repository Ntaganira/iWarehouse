package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityDecision;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import com.ntaganira.heritier.iWarehouse.enums.SaleApprovalKind;
import com.ntaganira.heritier.iWarehouse.enums.SaleApprovalStatus;
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
 * - File      : SaleApproval.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A request for a manager's approval at the counter, with the before and after values (AUD):
 *               a line's price cut beyond the cashier's discount limit (list price, price asked, discount,
 *               the line's amount before and after, POS-06) or customer credit beyond what the customer has
 *               left (limit, owed, credit asked, POS-05). The requester gives the reason; another person
 *               approves or rejects it. Kept with the invoice once it is paid.
 * </pre>
 */
@Entity
@Table(name = "sale_approvals")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class SaleApproval extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private SaleApprovalKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SaleApprovalStatus status = SaleApprovalStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private SalesInvoice invoice;

    /** A price change: its line, cleared when the line leaves the sale. */
    @Column(name = "line_id")
    private UUID lineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    /** What it is about: the line ("U-WH-000012 · CLR-6 3210 x 2250") or the customer. */
    @Column(nullable = false, length = 200, updatable = false)
    private String subject;

    @Column(name = "list_price", precision = 18, scale = 2, updatable = false)
    private BigDecimal listPrice;

    @Column(name = "requested_price", precision = 18, scale = 2, updatable = false)
    private BigDecimal requestedPrice;

    @Column(name = "discount_percent", precision = 5, scale = 2, updatable = false)
    private BigDecimal discountPercent;

    /** The requester's discount limit when asking. */
    @Column(name = "limit_percent", precision = 5, scale = 2, updatable = false)
    private BigDecimal limitPercent;

    @Column(name = "amount_before", precision = 18, scale = 2, updatable = false)
    private BigDecimal amountBefore;

    @Column(name = "amount_after", precision = 18, scale = 2, updatable = false)
    private BigDecimal amountAfter;

    @Column(name = "credit_limit", precision = 18, scale = 2, updatable = false)
    private BigDecimal creditLimit;

    @Column(precision = 18, scale = 2, updatable = false)
    private BigDecimal owed;

    @Column(name = "credit_amount", precision = 18, scale = 2, updatable = false)
    private BigDecimal creditAmount;

    @Column(nullable = false, length = 200, updatable = false)
    private String reason;

    @Column(name = "requested_by", nullable = false, length = 50, updatable = false)
    private String requestedBy;

    @Column(name = "requested_by_id", updatable = false)
    private Long requestedById;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** Why it was rejected or withdrawn, or the approver's note. */
    @Column(name = "decision_note", length = 200)
    private String decisionNote;

    /** Approved in part (F-03): a price between the one asked and the list price. */
    @Column(name = "approved_price", precision = 18, scale = 2)
    private BigDecimal approvedPrice;

    /** Approved in part: less credit than asked. */
    @Column(name = "approved_credit", precision = 18, scale = 2)
    private BigDecimal approvedCredit;

    /** A price below what the glass cost (SDP 8): it needs the office set for that. */
    @Column(name = "below_cost", nullable = false, updatable = false)
    private boolean belowCost;

    /** What the glass of the line cost, net of VAT, when the price is below it. */
    @Column(name = "cost_amount", precision = 18, scale = 2, updatable = false)
    private BigDecimal costAmount;

    /** CREDIT, TERMS: owed past due when asked, and how late the oldest of it was. */
    @Column(name = "overdue_amount", precision = 18, scale = 2, updatable = false)
    private BigDecimal overdueAmount;

    @Column(name = "oldest_overdue_days", updatable = false)
    private Integer oldestOverdueDays;

    /** TERMS: days added to the customer's terms for this invoice. */
    @Column(name = "extra_days", updatable = false)
    private Integer extraDays;

    /** The lowest office that may decide it, when it was asked (Delegation of Authority). */
    @Enumerated(EnumType.STRING)
    @Column(name = "authority_level", length = 10)
    private AuthorityLevel authorityLevel;

    @Column(name = "recommended_by", length = 50)
    private String recommendedBy;

    @Column(name = "recommended_by_id")
    private Long recommendedById;

    @Column(name = "recommended_at")
    private LocalDateTime recommendedAt;

    @Column(name = "recommendation_note", length = 200)
    private String recommendationNote;

    /** Waiting for a decision (recommended or not). */
    public boolean isPending() {
        return SaleApprovalStatus.WAITING.contains(status);
    }

    public boolean isRecommended() {
        return status == SaleApprovalStatus.RECOMMENDED;
    }

    public boolean isTerms() {
        return kind == SaleApprovalKind.TERMS;
    }

    /** The price that applies once approved: the one approved in part, or the one asked. */
    public BigDecimal getEffectivePrice() {
        return approvedPrice != null ? approvedPrice : requestedPrice;
    }

    /** The credit allowed once approved: what was approved in part, or what was asked. */
    public BigDecimal getEffectiveCredit() {
        return approvedCredit != null ? approvedCredit : creditAmount;
    }

    public boolean isApproved() {
        return status == SaleApprovalStatus.APPROVED;
    }

    public boolean isPrice() {
        return kind == SaleApprovalKind.PRICE;
    }

    public boolean isCredit() {
        return kind == SaleApprovalKind.CREDIT;
    }

    /** Credit: how far past the limit the customer goes with it. */
    public BigDecimal getOverLimit() {
        return isCredit() ? owed.add(creditAmount).subtract(creditLimit).max(BigDecimal.ZERO) : null;
    }

    /** The decision of the Delegation of Authority it is: a discount, a price below cost, or credit. */
    public AuthorityDecision getAuthorityDecision() {
        return isPrice() ? (belowCost ? AuthorityDecision.BELOW_COST : AuthorityDecision.DISCOUNT) : AuthorityDecision.CREDIT_EXCEPTION;
    }

    /** What the Delegation of Authority weighs: the discount %, the sale at stake below cost, or the credit at stake. */
    public BigDecimal getAuthorityAmount() {
        if (isPrice()) {
            return belowCost ? amountAfter : discountPercent;
        }
        if (isCredit()) {
            return overdueAmount != null && overdueAmount.signum() > 0 ? creditAmount : getOverLimit();
        }
        return amountAfter;
    }
}
