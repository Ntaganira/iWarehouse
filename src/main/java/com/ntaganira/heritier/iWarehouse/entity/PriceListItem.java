package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PriceListItem.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Price per m² of one product on one price list (MD-06). A cleared price stays as a row
 *               with no price, so the list's price history keeps every change.
 * </pre>
 */
@Entity
@Table(name = "price_list_items")
@AuditedEntity
@Getter
@Setter
@NoArgsConstructor
public class PriceListItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_list_id", nullable = false, updatable = false)
    private PriceList priceList;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    /** RWF per m²; null = not priced on this list. */
    @Column(name = "price_per_m2", precision = 18, scale = 2)
    private BigDecimal pricePerM2;
}
