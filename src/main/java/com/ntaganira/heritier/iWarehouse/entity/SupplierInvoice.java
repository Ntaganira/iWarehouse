package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
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
 * - File      : SupplierInvoice.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A supplier's invoice (ACC-09) matched to the supplier's posted goods receipts it bills, each once
 *               (SupplierInvoiceLine): their reference, dates (due = invoice date + the supplier's terms), the
 *               currency and the total in it, and the RWF the receipts were booked at. Posting moves each receipt
 *               from Goods Received Not Invoiced to Accounts Payable at its own value and rate. Never changed.
 * </pre>
 */
@Entity
@Table(name = "supplier_invoices")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class SupplierInvoice extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false, updatable = false)
    private Supplier supplier;

    /** The supplier's own invoice number. */
    @Column(name = "supplier_ref", nullable = false, length = 60, updatable = false)
    private String supplierRef;

    @Column(name = "invoice_date", nullable = false, updatable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private String currencyCode;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "base_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal baseAmount;

    @Column(length = 255, updatable = false)
    private String notes;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private LocalDateTime postedAt;

    @Column(name = "posted_by", nullable = false, length = 50, updatable = false)
    private String postedBy;
}
