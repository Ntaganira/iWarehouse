package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
 * - File      : StaffAdvance.java
 * - Date      : 2026. 10. 16.
 * - User      : Hntaganira
 * - Desc      : A staff advance (CMP 08; ADV-WH-2026-000001): an employee, what it is for (a journey with its per diem, or
 *               anything else), its cost centre and amount in RWF (a foreign per diem converted at the rate kept here).
 *               Approved by the Managing Director, paid (vault, bank, mobile money or a petty cash fund), then retired by
 *               its due day with what it paid for and the receipts, the rest returned or paid back; not retired in time,
 *               the next pay runs recover it from salary (recovered).
 * </pre>
 */
@Entity
@Table(name = "staff_advances")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class StaffAdvance extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StaffAdvanceKind kind = StaffAdvanceKind.TRAVEL;

    @Column(nullable = false, length = 255)
    private String purpose;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cost_centre_id", nullable = false)
    private CostCentre costCentre;

    @Enumerated(EnumType.STRING)
    @Column(name = "per_diem_category", length = 15)
    private PerDiemCategory perDiemCategory;

    @Column(length = 100)
    private String destination;

    @Column(name = "depart_on")
    private LocalDate departOn;

    @Column(name = "return_on")
    private LocalDate returnOn;

    private Integer days;

    private Integer nights;

    @Column(name = "per_diem_currency", length = 3)
    private String perDiemCurrency;

    @Column(name = "per_diem_amount", precision = 18, scale = 2)
    private BigDecimal perDiemAmount;

    @Column(precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(name = "rate_date")
    private LocalDate rateDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_source", length = 10)
    private RateSource rateSource;

    /** RWF: what is paid. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private StaffAdvanceStatus status = StaffAdvanceStatus.DRAFT;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "authority_level", length = 10)
    private AuthorityLevel authorityLevel;

    @Column(name = "paid_by", length = 50)
    private String paidBy;

    @Column(name = "paid_by_id")
    private Long paidById;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_method", length = 15)
    private AdvancePayMethod payMethod;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pay_fund_id")
    private PettyCashFund payFund;

    @Column(name = "pay_reference", length = 60)
    private String payReference;

    @Column(name = "pay_journal_id")
    private UUID payJournalId;

    /** Retired with receipts by this day (CMP 08: ten working days). */
    @Column(name = "due_on")
    private LocalDate dueOn;

    @Column(name = "retired_by", length = 50)
    private String retiredBy;

    @Column(name = "retired_by_id")
    private Long retiredById;

    @Column(name = "retired_on")
    private LocalDate retiredOn;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    /** What the receipts show it paid for. */
    @Column(precision = 18, scale = 2)
    private BigDecimal spent;

    /** The rest: returned by the employee (positive) or paid back to them (negative). */
    @Column(precision = 18, scale = 2)
    private BigDecimal settled;

    @Enumerated(EnumType.STRING)
    @Column(name = "settle_method", length = 15)
    private AdvancePayMethod settleMethod;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settle_fund_id")
    private PettyCashFund settleFund;

    @Column(name = "settle_reference", length = 60)
    private String settleReference;

    @Column(name = "retire_journal_id")
    private UUID retireJournalId;

    /** Taken from salary by posted pay runs. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal recovered = BigDecimal.ZERO;

    @OneToMany(mappedBy = "advance", cascade = CascadeType.ALL)
    @OrderBy("lineNo")
    private List<StaffAdvanceLine> lines = new ArrayList<>();

    public boolean isDraft() {
        return status == StaffAdvanceStatus.DRAFT;
    }

    public boolean isPaid() {
        return status == StaffAdvanceStatus.PAID;
    }

    /** Paid and still owed by the employee: the amount less what salary recovered. */
    public BigDecimal getOutstanding() {
        return status == StaffAdvanceStatus.PAID ? amount.subtract(recovered) : BigDecimal.ZERO;
    }

    /** Paid, not retired, its due day passed. */
    public boolean isOverdue(LocalDate today) {
        return status == StaffAdvanceStatus.PAID && dueOn != null && today.isAfter(dueOn);
    }
}
