package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.CostType;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import com.ntaganira.heritier.iWarehouse.enums.ShipmentCostStatus;
import com.ntaganira.heritier.iWarehouse.service.CurrencyMath;
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
 * - File      : ShipmentCost.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : One import cost of a shipment (PRC-03): a bill for freight, insurance, duty, clearing,
 *               port or transport, in its own currency. Posting fixes the rate of the bill's date
 *               (customs rate for duty, PRC-05) and allocates it; a posted line is never changed, a
 *               negative line (credit note) corrects it.
 * </pre>
 */
@Entity
@Table(name = "shipment_costs")
@AuditedEntity(ref = "lineNo")
@Getter
@Setter
@NoArgsConstructor
public class ShipmentCost extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false, updatable = false)
    private Shipment shipment;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_type", nullable = false, length = 12)
    private CostType costType;

    @Column(length = 120)
    private String description;

    /** Paid to: carrier, insurer, clearing agent, RRA. Optional. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(name = "invoice_ref", length = 60)
    private String invoiceRef;

    /** The bill's date: its exchange rate. */
    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    /** In currencyCode; negative for a credit note. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ShipmentCostStatus status = ShipmentCostStatus.DRAFT;

    @Column(precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(name = "rate_date")
    private LocalDate rateDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_source", length = 10)
    private RateSource rateSource;

    @Column(name = "posting_no")
    private Integer postingNo;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "posted_by", length = 50)
    private String postedBy;

    /** RWF at the posted rate, unrounded (the posting rounds its total); null for a draft. */
    public BigDecimal getAmountBase() {
        return rate == null ? null : CurrencyMath.toBase(amount, rate);
    }
}
