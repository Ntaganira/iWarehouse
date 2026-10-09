package com.ntaganira.heritier.iWarehouse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : SupplierInvoiceLine.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A goods receipt a supplier invoice bills (ACC-09): its value in the invoice's currency, its rate
 *               and the RWF it was booked at. A receipt is billed once (uk_supplier_invoice_lines_receipt).
 *               Append-only (trg_supplier_invoice_lines_append_only): the invoice records it, so it is not audited.
 * </pre>
 */
@Entity
@Immutable
@Table(name = "supplier_invoice_lines")
@Getter
@Setter
@NoArgsConstructor
public class SupplierInvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "goods_receipt_id", nullable = false)
    private UUID goodsReceiptId;

    @Column(name = "receipt_number", nullable = false, length = 30)
    private String receiptNumber;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(name = "base_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal baseAmount;
}
