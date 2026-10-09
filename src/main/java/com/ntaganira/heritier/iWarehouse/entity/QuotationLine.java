package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.QuoteLineKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : QuotationLine.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : One line of a quotation (POS-03): whole sheets, a size to cut, or processing on a size (under
 *               it, parent_line_id). Priced like a sale line (LinePricing): the list price per m² (or per unit
 *               of the processing), the discount given, the price, the list and its VAT flag, the tax letter and
 *               rate, the amount whole RWF with VAT included.
 * </pre>
 */
@Entity
@Table(name = "quotation_lines")
@AuditedEntity(ref = "lineNo")
@Getter
@Setter
@NoArgsConstructor
public class QuotationLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private QuoteLineKind kind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_line_id")
    private QuotationLine parentLine;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private ProcessingService service;

    @Column(name = "width_mm", nullable = false)
    private int widthMm;

    @Column(name = "height_mm", nullable = false)
    private int heightMm;

    @Column(nullable = false)
    private int quantity;

    /** Holes per piece, for processing charged per hole. */
    private Integer holes;

    /** A size: the processing's codes, for the cutter. */
    @Column(length = 200)
    private String processing;

    /** A size: the customer's mark. */
    @Column(length = 60)
    private String mark;

    /** Glass: the chargeable area of one piece. */
    @Column(name = "chargeable_area_m2", precision = 10, scale = 4)
    private BigDecimal chargeableAreaM2;

    /** Processing: the m², metres, pieces or holes charged. */
    @Column(name = "service_quantity", precision = 10, scale = 4)
    private BigDecimal serviceQuantity;

    @Column(name = "list_price", nullable = false, precision = 18, scale = 2)
    private BigDecimal listPrice;

    @Column(name = "discount_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercent = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal price;

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

    public boolean isSheet() {
        return kind == QuoteLineKind.SHEET;
    }

    public boolean isCustomPiece() {
        return kind == QuoteLineKind.CUSTOM_PIECE;
    }

    public boolean isServiceLine() {
        return kind == QuoteLineKind.SERVICE;
    }
}
