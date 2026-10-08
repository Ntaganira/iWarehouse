package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.service.Pricing;
import com.ntaganira.heritier.iWarehouse.service.PurchaseOrders;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PurchaseOrderLine.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Sheets of one product and size on a purchase order (PRC-01), priced per m² in the
 *               order's currency. receivedQty counts the sheets delivered by posted receipts, broken
 *               ones included (the supplier shipped them).
 * </pre>
 */
@Entity
@Table(name = "purchase_order_lines")
@AuditedEntity(ref = "lineNo")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrderLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "width_mm", nullable = false)
    private int widthMm;

    @Column(name = "height_mm", nullable = false)
    private int heightMm;

    /** Sheets ordered. */
    @Column(nullable = false)
    private int quantity;

    @Column(name = "price_per_m2", nullable = false, precision = 18, scale = 4)
    private BigDecimal pricePerM2;

    @Column(name = "received_qty", nullable = false)
    private int receivedQty;

    public int getOutstanding() {
        return Math.max(quantity - receivedQty, 0);
    }

    /** m² of one sheet. */
    public BigDecimal getSheetArea() {
        return Pricing.areaM2(widthMm, heightMm);
    }

    /** m² of the line. */
    public BigDecimal getArea() {
        return PurchaseOrders.lineArea(quantity, widthMm, heightMm);
    }

    /** Amount in the order's currency, unrounded (the order rounds its total). */
    public BigDecimal getAmount() {
        return PurchaseOrders.lineAmount(quantity, widthMm, heightMm, pricePerM2);
    }
}
