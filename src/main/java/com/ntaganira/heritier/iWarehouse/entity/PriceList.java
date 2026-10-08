package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PriceList.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : A set of selling prices in RWF (MD-06): price per m² for each product and a price per
 *               unit for each processing service. Exactly one list is the default: it prices customers
 *               without a list, and whatever another list leaves unpriced. Deactivated, never deleted.
 * </pre>
 */
@Entity
@Table(name = "price_lists")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class PriceList extends BaseEntity {

    @Column(nullable = false, unique = true, length = 20, updatable = false)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    /** True: prices are what the customer pays, VAT included (usual at the counter). */
    @Column(name = "prices_include_vat", nullable = false)
    private boolean pricesIncludeVat = true;

    /** Smallest area charged per piece; null = Settings (pricing.min-chargeable-area). */
    @Column(name = "min_chargeable_m2", precision = 10, scale = 4)
    private BigDecimal minChargeableM2;

    @Column(name = "is_default", nullable = false)
    private boolean defaultList;

    @Column(length = 255)
    private String notes;

    @Column(nullable = false)
    private boolean enabled = true;
}
