package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.ConfirmationStatus;
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
 * - File      : BalanceConfirmation.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : A customer asked to confirm their balance on a day (SDP 10-11, BP Table 9 and 11): sent by someone
 *               independent of the receivables ledger (the Internal Controller), the letter with its reply slip printed
 *               from it; the reply recorded once: agreed, or disputed with the balance the customer states and their
 *               explanation (the Director of Finance told). One waiting per customer.
 * </pre>
 */
@Entity
@Table(name = "balance_confirmations")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class BalanceConfirmation extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @Column(name = "as_of", nullable = false, updatable = false)
    private LocalDate asOf;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ConfirmationStatus status = ConfirmationStatus.SENT;

    @Column(name = "sent_by", nullable = false, length = 50, updatable = false)
    private String sentBy;

    @Column(name = "sent_by_id", updatable = false)
    private Long sentById;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private LocalDateTime sentAt;

    @Column(name = "replied_on")
    private LocalDate repliedOn;

    @Column(name = "reply_balance", precision = 18, scale = 2)
    private BigDecimal replyBalance;

    @Column(name = "reply_note", length = 500)
    private String replyNote;

    @Column(name = "reply_recorded_by", length = 50)
    private String replyRecordedBy;

    @Column(name = "reply_recorded_by_id")
    private Long replyRecordedById;

    @Column(name = "reply_recorded_at")
    private LocalDateTime replyRecordedAt;

    public boolean isWaiting() {
        return status == ConfirmationStatus.SENT;
    }

    /** What the customer's balance differs by (theirs less ours); zero when agreed. */
    public BigDecimal getDifference() {
        return replyBalance == null ? BigDecimal.ZERO : replyBalance.subtract(balance);
    }
}
