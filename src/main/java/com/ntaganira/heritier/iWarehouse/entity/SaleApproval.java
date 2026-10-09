package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
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

    public boolean isPending() {
        return status == SaleApprovalStatus.PENDING;
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
}
