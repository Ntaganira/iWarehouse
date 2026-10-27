package com.ntaganira.heritier.iWarehouse.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PriceChangeLine.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : One price of a price change (SDP 5): a glass's price per m² or a processing price, from the old to the new
 *               (null = not priced on the list). Written once with its change and never altered (append-only), so what
 *               was approved is what was asked.
 * </pre>
 */
@Entity
@Table(name = "price_change_lines")
@Immutable
@Getter
@Setter
@NoArgsConstructor
public class PriceChangeLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "change_id", nullable = false)
    private UUID changeId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private ProcessingService service;

    @Column(name = "old_price", precision = 18, scale = 2)
    private BigDecimal oldPrice;

    @Column(name = "new_price", precision = 18, scale = 2)
    private BigDecimal newPrice;

    public boolean isGlass() {
        return product != null;
    }
}
