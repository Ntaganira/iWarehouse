package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.SaleLineKind;
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
 * - Desc      : One line of a sale: a unit from stock, priced per m² from the customer's price list over its
 *               chargeable area (MD-06), with the list, its VAT flag and the glass's tax letter and rate kept
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
}
