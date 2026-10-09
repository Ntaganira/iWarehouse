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
 *               kept: tendered less change) and its reference. Written when the invoice is paid and never
 *               changed (trg_sales_payments_append_only): each row is its own record, so it is not audited.
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

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false, length = 50)
    private String username;
}
