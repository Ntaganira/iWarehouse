package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.SaleChannel;
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
import java.util.UUID;

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
 *               notes (POS-09). Its payments are SalesPayment rows written when it is paid. An order paid by a
 *               deposit (POS-08) is issued with a balance due, paid at collection before its pieces are handed over.
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

    /** The till that rang it up; none for a sale from a vehicle. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "till_session_id")
    private TillSession tillSession;

    /** At the counter, or from a vehicle on the mobile POS (MPOS). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private SaleChannel channel = SaleChannel.COUNTER;

    /** The trip a mobile sale was made on. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", updatable = false)
    private Trip trip;

    /** The UUID the phone gave the sale (SYNC-03): the same sale sent twice is taken once. */
    @Column(name = "client_id", unique = true, updatable = false)
    private UUID clientId;

    /** The phone that made it (AUD-07). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", updatable = false)
    private ApiDevice device;

    /** When the phone made it, on its own clock (AUD-07). */
    @Column(name = "client_created_at", updatable = false)
    private LocalDateTime clientCreatedAt;

    /** When the server took it. */
    @Column(name = "synced_at", updatable = false)
    private LocalDateTime syncedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "buyer_name", length = 100)
    private String buyerName;

    @Column(name = "buyer_tin", length = 9)
    private String buyerTin;

    /** The buyer's EBM purchase code (prcOrdCd), given with their TIN. */
    @Column(name = "purchase_code", length = 6)
    private String purchaseCode;

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

    /** What the customer still owes once issued (POS-08): the balance of a deposit, 0 once paid in full. */
    @Column(name = "balance_due", precision = 18, scale = 2)
    private BigDecimal balanceDue;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "posted_by", length = 50)
    private String postedBy;

    /** The quotation it was rung up from (POS-03), converted when this sale is paid. */
    @Column(name = "quotation_id")
    private UUID quotationId;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<SalesInvoiceLine> lines = new ArrayList<>();

    public boolean isDraft() {
        return status == SalesInvoiceStatus.DRAFT;
    }

    public boolean isMobile() {
        return channel == SaleChannel.MOBILE;
    }

    /** An issued invoice with a balance still to pay (POS-08). */
    public boolean hasBalanceDue() {
        return balanceDue != null && balanceDue.signum() > 0;
    }

    /** What was paid so far: the total less the balance due. */
    public BigDecimal getAmountPaid() {
        return totalAmount == null ? null : totalAmount.subtract(balanceDue == null ? BigDecimal.ZERO : balanceDue);
    }

    /** The name the invoice is made out to: the buyer's, or the customer's. */
    public String getBillTo() {
        return buyerName != null ? buyerName : customer.getName();
    }
}
