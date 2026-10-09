package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.SalesInvoiceStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : SalesInvoice.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A counter sale (POS-01, POS-04): a draft of its till session while it is rung up (one per
 *               till; its units held), then the invoice once paid, with its number, the buyer's name and
 *               TIN (TAX-04) and its totals in RWF. A posted invoice never changes: corrections are credit
 *               notes (POS-09). Its payments are SalesPayment rows written when it is paid.
 * </pre>
 */
@Entity
@Table(name = "sales_invoices")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class SalesInvoice extends BaseEntity {

    @Column(unique = true, length = 30)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SalesInvoiceStatus status = SalesInvoiceStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "till_session_id", nullable = false)
    private TillSession tillSession;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "buyer_name", length = 100)
    private String buyerName;

    @Column(name = "buyer_tin", length = 9)
    private String buyerTin;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "net_amount", precision = 18, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "vat_amount", precision = 18, scale = 2)
    private BigDecimal vatAmount;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "cash_tendered", precision = 18, scale = 2)
    private BigDecimal cashTendered;

    @Column(name = "change_given", precision = 18, scale = 2)
    private BigDecimal changeGiven;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "posted_by", length = 50)
    private String postedBy;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<SalesInvoiceLine> lines = new ArrayList<>();

    public boolean isDraft() {
        return status == SalesInvoiceStatus.DRAFT;
    }

    /** The name the invoice is made out to: the buyer's, or the customer's. */
    public String getBillTo() {
        return buyerName != null ? buyerName : customer.getName();
    }
}
