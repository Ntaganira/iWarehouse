package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.QuotationStatus;
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
 * - File      : Quotation.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A quotation (POS-03): a customer, the name and TIN to print, a validity date, and lines priced
 *               from the customer's list with their totals (VAT per tax letter). Edited while a draft; sent, it is
 *               fixed; rung up at a till it becomes the sale, converted once that sale is paid (its invoice kept);
 *               cancelled with a reason.
 * </pre>
 */
@Entity
@Table(name = "quotations")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class Quotation extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private QuotationStatus status = QuotationStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "buyer_name", length = 100)
    private String buyerName;

    @Column(name = "buyer_tin", length = 9)
    private String buyerTin;

    @Column(name = "quote_date", nullable = false, updatable = false)
    private LocalDate quoteDate;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    @Column(length = 500)
    private String notes;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "sent_by", length = 50)
    private String sentBy;

    /** The invoice it became. */
    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "converted_at")
    private LocalDateTime convertedAt;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by", length = 50)
    private String cancelledBy;

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<QuotationLine> lines = new ArrayList<>();

    public boolean isDraft() {
        return status == QuotationStatus.DRAFT;
    }

    public boolean isSent() {
        return status == QuotationStatus.SENT;
    }

    /** Sent and past its validity date: its prices no longer hold. */
    public boolean isExpiredOn(LocalDate day) {
        return status == QuotationStatus.SENT && validUntil.isBefore(day);
    }

    public String getBillTo() {
        return buyerName != null ? buyerName : customer.getName();
    }
}
