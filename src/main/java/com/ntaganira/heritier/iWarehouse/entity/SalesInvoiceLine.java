package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.SaleLineKind;
import com.ntaganira.heritier.iWarehouse.service.Discounts;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : SalesInvoiceLine.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : One line of a sale: a unit from stock or a size to cut (POS-02), priced per m² from the
 *               customer's price list over its chargeable area (MD-06), or processing on a size, priced per
 *               m², metre of edge, piece or hole. The list, its VAT flag and the tax letter and rate are kept
 *               on the line (TAX-01). The amount is whole RWF, VAT included. Fixed once the invoice is issued
 *               (trg_sales_invoice_lines_posted).
 * </pre>
 */
@Entity
@Table(name = "sales_invoice_lines")
@AuditedEntity(ref = "unitCode")
@Getter
@Setter
@NoArgsConstructor
public class SalesInvoiceLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private SalesInvoice invoice;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private SaleLineKind kind = SaleLineKind.STOCK_UNIT;

    @Column(name = "stock_unit_id")
    private UUID stockUnitId;

    @Column(name = "unit_code", length = 30)
    private String unitCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "width_mm", nullable = false)
    private int widthMm;

    @Column(name = "height_mm", nullable = false)
    private int heightMm;

    @Column(nullable = false)
    private int quantity = 1;

    @Column(name = "chargeable_area_m2", nullable = false, precision = 10, scale = 4)
    private BigDecimal chargeableAreaM2;

    @Column(name = "price_per_m2", nullable = false, precision = 18, scale = 2)
    private BigDecimal pricePerM2;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_list_id", nullable = false)
    private PriceList priceList;

    @Column(name = "prices_include_vat", nullable = false)
    private boolean pricesIncludeVat;

    @Column(name = "tax_code", nullable = false, length = 1)
    private String taxCode;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRate;

    /** Whole RWF, VAT included. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /** A service line: the size it is done on. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_line_id")
    private SalesInvoiceLine parentLine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private ProcessingService service;

    /** A service line: the m², metres, pieces or holes charged. */
    @Column(name = "service_quantity", precision = 10, scale = 4)
    private BigDecimal serviceQuantity;

    @Column(name = "service_unit_price", precision = 18, scale = 2)
    private BigDecimal serviceUnitPrice;

    /** Holes per piece, for a service charged per hole. */
    private Integer holes;

    /** A size: the services' codes, for the cutter. */
    @Column(length = 200)
    private String processing;

    /** A size: the customer's mark. */
    @Column(length = 60)
    private String mark;

    /** A price changed at the counter (POS-06): the list price it replaced, per m² or per unit of the service. */
    @Column(name = "list_price", precision = 18, scale = 2)
    private BigDecimal listPrice;

    /** Why the price was changed. */
    @Column(name = "price_reason", length = 200)
    private String priceReason;

    public boolean isStockUnit() {
        return kind == SaleLineKind.STOCK_UNIT;
    }

    public boolean isCustomPiece() {
        return kind == SaleLineKind.CUSTOM_PIECE;
    }

    public boolean isServiceLine() {
        return kind == SaleLineKind.SERVICE;
    }

    /** The price charged: per m² for glass, per unit of the service for processing. */
    public BigDecimal getPrice() {
        return isServiceLine() ? serviceUnitPrice : pricePerM2;
    }

    public boolean isPriceChanged() {
        return listPrice != null;
    }

    /** The discount on the list price, in percent (negative when the price was raised); null when unchanged. */
    public BigDecimal getDiscountPercent() {
        return listPrice == null ? null : Discounts.percent(listPrice, getPrice());
    }

    /** What the line is, for an approval request and the activity log: its label, or the size or processing. */
    public String getLabel() {
        if (isStockUnit()) {
            return unitCode + " · " + product.getCode() + " " + widthMm + " x " + heightMm;
        }
        String size = product.getCode() + " " + widthMm + " x " + heightMm + " x " + quantity;
        return isServiceLine() ? service.getName() + " · " + size : size;
    }
}
