package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.GoodsReceiptStatus;
import com.ntaganira.heritier.iWarehouse.enums.RateSource;
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
 * - File      : GoodsReceipt.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Goods received note against a purchase order (PRC-02): one crate batch per crate.
 *               Posting fixes the exchange rate of the receipt date (currency, rate, rate date, source,
 *               ACC-02) and creates the stock units; a posted receipt is never edited.
 * </pre>
 */
@Entity
@Table(name = "goods_receipts")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class GoodsReceipt extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GoodsReceiptStatus status = GoodsReceiptStatus.DRAFT;

    @Column(name = "received_date", nullable = false)
    private LocalDate receivedDate;

    /** Delivery note, packing list or container number. */
    @Column(name = "delivery_ref", length = 60)
    private String deliveryRef;

    @Column(length = 500)
    private String notes;

    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private String currencyCode;

    @Column(precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(name = "rate_date")
    private LocalDate rateDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_source", length = 10)
    private RateSource rateSource;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "posted_by", length = 50)
    private String postedBy;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @OneToMany(mappedBy = "goodsReceipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt, batchNo")
    private List<CrateBatch> crates = new ArrayList<>();

    public int getSheets() {
        return crates.stream().mapToInt(CrateBatch::getSheets).sum();
    }

    public int getBroken() {
        return crates.stream().mapToInt(CrateBatch::getBroken).sum();
    }

    /** m² of the good sheets. */
    public BigDecimal getArea() {
        return crates.stream().map(CrateBatch::getArea).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
