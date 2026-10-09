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
 * - File      : CreditNote.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A credit note against an issued invoice (POS-09): the glass the customer brought back (its units,
 *               CreditNoteUnit), what each invoice line is credited (CreditNoteLine), its totals with VAT, the part
 *               that reduced the invoice's balance due and how the rest was refunded (cash from a till, mobile
 *               money, card, transfer, or to the customer's account: CREDIT). Posted when saved, with a reason;
 *               never changed: a mistake is corrected by a new sale.
 * </pre>
 */
@Entity
@Table(name = "credit_notes")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class CreditNote extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private SalesInvoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @Column(name = "credit_date", nullable = false, updatable = false)
    private LocalDate creditDate;

    @Column(nullable = false, length = 255, updatable = false)
    private String reason;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal netAmount;

    @Column(name = "vat_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal vatAmount;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal totalAmount;

    /** The part of the credit that reduced the invoice's balance due (POS-08). */
    @Column(name = "balance_reduced", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal balanceReduced;

    /** How the rest went back; CREDIT = to the customer's account. Null when nothing was refunded. */
    @Enumerated(EnumType.STRING)
    @Column(name = "refund_method", length = 15, updatable = false)
    private PaymentMethod refundMethod;

    @Column(name = "refund_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal refundAmount;

    @Column(name = "refund_reference", length = 60, updatable = false)
    private String refundReference;

    /** A cash refund: the till it left. */
    @Column(name = "till_session_id", updatable = false)
    private UUID tillSessionId;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private LocalDateTime postedAt;

    @Column(name = "posted_by", nullable = false, length = 50, updatable = false)
    private String postedBy;

    public boolean isRefunded() {
        return refundMethod != null;
    }
}
