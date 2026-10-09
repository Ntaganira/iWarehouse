package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : SalesPayment.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : One part of a sale's payment (POS-04): the method, the amount it settles (for cash, what was
 *               kept: tendered less change, with what was handed over) and its reference, in the till that took
 *               it. Written when the invoice is paid, or its balance (POS-08), and never changed
 *               (trg_sales_payments_append_only): each row is its own record, so it is not audited.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "sales_payments")
@Getter
@Setter
@NoArgsConstructor
public class SalesPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private PaymentMethod method;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(length = 60)
    private String reference;

    /** The till that took it: the balance of an order is often paid in another till than the sale. */
    @Column(name = "till_session_id", nullable = false)
    private UUID tillSessionId;

    /** A payment of the balance, after the invoice was issued (POS-08). */
    @Column(name = "balance_payment", nullable = false)
    private boolean balancePayment;

    /** Cash: what the customer handed over (the change is the difference). */
    @Column(name = "cash_tendered", precision = 18, scale = 2)
    private BigDecimal cashTendered;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false, length = 50)
    private String username;

    /** The change given back on this cash payment, if it says what was handed over. */
    public BigDecimal getChange() {
        return cashTendered == null ? null : cashTendered.subtract(amount);
    }
}
