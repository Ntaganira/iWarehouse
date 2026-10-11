package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.PayRunStatus;
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
 * - File      : PayRun.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : A month's pay run (payroll, PAY-WH-2026-000001): a line per employee, the rates in force, the totals.
 *               Prepared and submitted, approved by another person (its journal posts on the month's last day), then paid
 *               (the payment journal). Posted, it never changes: a correction goes into the next run. One run a month;
 *               a cancelled one gives the month back.
 * </pre>
 */
@Entity
@Table(name = "pay_runs")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class PayRun extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    /** The month's first day. */
    @Column(nullable = false, updatable = false)
    private LocalDate period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PayRunStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rate_set_id", nullable = false)
    private PayrollRateSet rateSet;

    @Column(nullable = false)
    private int employees;

    @Column(name = "total_gross", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalGross = BigDecimal.ZERO;

    @Column(name = "total_paye", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalPaye = BigDecimal.ZERO;

    /** Pension and maternity, the employees' shares. */
    @Column(name = "total_rssb_employee", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalRssbEmployee = BigDecimal.ZERO;

    @Column(name = "total_cbhi", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalCbhi = BigDecimal.ZERO;

    /** Advances, driver shortages and other deductions. */
    @Column(name = "total_other", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalOther = BigDecimal.ZERO;

    @Column(name = "total_net", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalNet = BigDecimal.ZERO;

    /** Pension, occupational hazards and maternity, the employer's shares. */
    @Column(name = "total_employer", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalEmployer = BigDecimal.ZERO;

    @Column(name = "prepared_by_id", updatable = false)
    private Long preparedById;

    @Column(name = "prepared_by", nullable = false, length = 50, updatable = false)
    private String preparedBy;

    @Column(name = "prepared_at", nullable = false, updatable = false)
    private LocalDateTime preparedAt;

    @Column(name = "submitted_by_id")
    private Long submittedById;

    @Column(name = "submitted_by", length = 50)
    private String submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /** The last time it was sent back, by whom and why. */
    @Column(name = "returned_by", length = 50)
    private String returnedBy;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "returned_note", length = 255)
    private String returnedNote;

    @Column(name = "approved_by", length = 50)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** The month's last day: the journal's date. */
    @Column(name = "posting_date")
    private LocalDate postingDate;

    @Column(name = "journal_id")
    private UUID journalId;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(name = "paid_by", length = 50)
    private String paidBy;

    @Column(name = "payment_reference", length = 60)
    private String paymentReference;

    @Column(name = "payment_journal_id")
    private UUID paymentJournalId;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "cancelled_by", length = 50)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** The month's last day. */
    public LocalDate getPeriodEnd() {
        return period.plusMonths(1).minusDays(1);
    }

    public boolean isDraft() {
        return status == PayRunStatus.DRAFT;
    }

    public boolean isPosted() {
        return status.isPosted();
    }

    /** What is owed to RSSB for the month: both shares of the contributions and CBHI. */
    public BigDecimal getTotalRssb() {
        return totalRssbEmployee.add(totalEmployer).add(totalCbhi);
    }

    /** What the month costs the business: gross pay and the employer's contributions. */
    public BigDecimal getTotalCost() {
        return totalGross.add(totalEmployer);
    }
}
