package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.service.Pricing;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : CrateBatch.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : One crate on a goods receipt (SRS 6.1 CrateBatch): sheets of one purchase order line,
 *               all the same size, put on one rack. Each good sheet becomes a stock unit when the
 *               receipt is posted; broken ones are only counted, for the supplier claim (PRC-06).
 *               costPerM2 is the PO price in RWF at posting; the import cost sheet adds landed costs.
 * </pre>
 */
@Entity
@Table(name = "crate_batches")
@AuditedEntity(ref = "batchNo")
@Getter
@Setter
@NoArgsConstructor
public class CrateBatch extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goods_receipt_id", nullable = false, updatable = false)
    private GoodsReceipt goodsReceipt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "po_line_id", nullable = false)
    private PurchaseOrderLine poLine;

    /** The PO line's product, kept here for stock queries. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Crate marking. */
    @Column(name = "batch_no", nullable = false, length = 40)
    private String batchNo;

    @Column(name = "width_mm", nullable = false)
    private int widthMm;

    @Column(name = "height_mm", nullable = false)
    private int heightMm;

    /** Good sheets: one stock unit each. */
    @Column(nullable = false)
    private int sheets;

    /** Found broken on arrival: no stock unit (PRC-06). */
    @Column(nullable = false)
    private int broken;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @Column(name = "cost_per_m2", precision = 18, scale = 4)
    private BigDecimal costPerM2;

    /** m² of one sheet. */
    public BigDecimal getSheetArea() {
        return Pricing.areaM2(widthMm, heightMm);
    }

    /** m² of the good sheets. */
    public BigDecimal getArea() {
        return getSheetArea().multiply(BigDecimal.valueOf(sheets));
    }

    /** m² of the sheets broken on arrival. */
    public BigDecimal getBrokenArea() {
        return getSheetArea().multiply(BigDecimal.valueOf(broken));
    }
}
