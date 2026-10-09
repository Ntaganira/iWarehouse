package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
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
 * - File      : SupplierPayment.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A payment to a supplier (ACC-08, ACC-09): in a currency at that day's rate (kept: currency,
 *               rate, date, source), by bank transfer, cash from the vault or mobile money. It settles the oldest
 *               open items in that currency: what they were booked at in RWF (settled base) less the RWF paid is
 *               the realised FX gain (positive) or loss. Posted when saved, never changed.
 * </pre>
 */
@Entity
@Table(name = "supplier_payments")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class SupplierPayment extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "payment_date", nullable = false, updatable = false)
    private LocalDate paymentDate;

    /** BANK_TRANSFER, CASH (from the main cash vault) or MOBILE_MONEY. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15, updatable = false)
    private PaymentMethod method;

    @Column(length = 60, updatable = false)
    private String reference;

    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private String currencyCode;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(precision = 18, scale = 6, updatable = false)
    private BigDecimal rate;

    @Column(name = "rate_date", updatable = false)
    private LocalDate rateDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_source", length = 10, updatable = false)
    private RateSource rateSource;

    /** RWF paid. */
    @Column(name = "base_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal baseAmount;

    /** RWF the settled items were booked at. */
    @Column(name = "settled_base", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal settledBase;

    /** Settled base less the RWF paid: positive a gain, negative a loss (ACC-08). */
    @Column(name = "fx_gain_loss", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal fxGainLoss;

    @Column(length = 255, updatable = false)
    private String notes;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private LocalDateTime postedAt;

    @Column(name = "posted_by", nullable = false, length = 50, updatable = false)
    private String postedBy;
}
