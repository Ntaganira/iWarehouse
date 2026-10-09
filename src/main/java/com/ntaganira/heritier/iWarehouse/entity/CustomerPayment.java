package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
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
 * - File      : CustomerPayment.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A payment on a customer's account (ACC-09): what they owed on credit sales, the balance of an order or
 *               anything else on the receivable account, paid in cash into the cashier's till (with what was handed
 *               over), by mobile money, card or bank transfer (with a reference). Posted when saved (Dr that account /
 *               Cr the customer's receivable), never changed: a mistake is corrected by a journal.
 * </pre>
 */
@Entity
@Table(name = "customer_payments")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class CustomerPayment extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @Column(name = "payment_date", nullable = false, updatable = false)
    private LocalDate paymentDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15, updatable = false)
    private PaymentMethod method;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(length = 60, updatable = false)
    private String reference;

    /** Cash: the till it went into. */
    @Column(name = "till_session_id", updatable = false)
    private UUID tillSessionId;

    /** Cash: what the customer handed over (the change is the difference). */
    @Column(name = "cash_tendered", precision = 18, scale = 2, updatable = false)
    private BigDecimal cashTendered;

    @Column(length = 255, updatable = false)
    private String notes;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private LocalDateTime postedAt;

    @Column(name = "posted_by", nullable = false, length = 50, updatable = false)
    private String postedBy;

    /** The change given back on a cash payment, if it says what was handed over. */
    public BigDecimal getChange() {
        return cashTendered == null ? null : cashTendered.subtract(amount);
    }
}
