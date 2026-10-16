package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.ntaganira.heritier.iWarehouse.enums.ExpenseNature;
import com.ntaganira.heritier.iWarehouse.enums.RequisitionStatus;
import com.ntaganira.heritier.iWarehouse.enums.SourcingMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PurchaseRequisition.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A purchase requisition (CMP 06, PRF-WH-2026-000001): what a department needs, its estimated cost, its
 *               budget code, the quotes its value calls for and the one chosen; approved by the head of department before
 *               any order or purchase bill commits the money. An order or a bill uses it once.
 * </pre>
 */
@Entity
@Table(name = "purchase_requisitions")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseRequisition extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cost_centre_id", nullable = false)
    private CostCentre costCentre;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(length = 1000)
    private String justification;

    @Column(name = "needed_by")
    private LocalDate neededBy;

    @Column(name = "budget_code", length = 30)
    private String budgetCode;

    @Column(nullable = false)
    private boolean budgeted = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ExpenseNature nature = ExpenseNature.OPEX;

    @Column(name = "estimated_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal estimatedTotal = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SourcingMethod sourcing = SourcingMethod.SINGLE_QUOTE;

    @Column(name = "sourcing_ref", length = 100)
    private String sourcingRef;

    @Column(name = "chosen_quote_id")
    private UUID chosenQuoteId;

    @Column(name = "chosen_reason", length = 500)
    private String chosenReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequisitionStatus status = RequisitionStatus.DRAFT;

    @Column(name = "requested_by", nullable = false, length = 50, updatable = false)
    private String requestedBy;

    @Column(name = "requested_by_id", updatable = false)
    private Long requestedById;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

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

    @Column(name = "used_by", length = 30)
    private String usedBy;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** Sets: the detail page fetches lines and quotes together (two lists would repeat rows). */
    @OneToMany(mappedBy = "requisition", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private Set<PurchaseRequisitionLine> lines = new LinkedHashSet<>();

    @OneToMany(mappedBy = "requisition", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("amount")
    private Set<RequisitionQuote> quotes = new LinkedHashSet<>();

    public boolean isDraft() {
        return status == RequisitionStatus.DRAFT;
    }

    public boolean isSubmitted() {
        return status == RequisitionStatus.SUBMITTED;
    }

    public boolean isApproved() {
        return status == RequisitionStatus.APPROVED;
    }

    /** The quote chosen, when one is. */
    public RequisitionQuote getChosenQuote() {
        return chosenQuoteId == null ? null : quotes.stream().filter(q -> chosenQuoteId.equals(q.getId())).findFirst().orElse(null);
    }

    public String getLabel() {
        return number + " · " + title;
    }
}
