package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.ReconciliationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : BankReconciliation.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A statement of the Bank or Mobile Money account reconciled with the ledger (ACC-12): its date and closing
 *               balance; the previous statement's balance plus the lines ticked (BankReconciliationLine) give it. The
 *               account's balance at that date and what was outstanding (deposits and payments not on the statement)
 *               are kept. Saved reconciled, never changed; the latest of an account can be cancelled with a reason.
 * </pre>
 */
@Entity
@Table(name = "bank_reconciliations")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class BankReconciliation extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(name = "statement_date", nullable = false, updatable = false)
    private LocalDate statementDate;

    @Column(name = "statement_balance", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal statementBalance;

    @Column(name = "previous_balance", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal previousBalance;

    /** Debits less credits of the lines ticked. */
    @Column(name = "cleared_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal clearedAmount;

    /** The account's balance at the statement date. */
    @Column(name = "book_balance", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal bookBalance;

    @Column(name = "outstanding_deposits", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal outstandingDeposits;

    @Column(name = "outstanding_payments", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal outstandingPayments;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ReconciliationStatus status;

    @Column(length = 255, updatable = false)
    private String notes;

    @Column(name = "reconciled_by", nullable = false, length = 50, updatable = false)
    private String reconciledBy;

    @Column(name = "reconciled_at", nullable = false, updatable = false)
    private LocalDateTime reconciledAt;

    @Column(name = "cancelled_by", length = 50)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    public boolean isReconciled() {
        return status == ReconciliationStatus.RECONCILED;
    }
}
