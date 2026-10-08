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
 * - File      : TaxCategory.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : VAT category set per product (TAX-01): standard 18%, zero-rated, exempt. ebmCode is the
 *               RRA EBM tax type sent for each invoice line (TAX-02). Deactivated, never deleted. The
 *               default category is used for new products and stays active.
 * </pre>
 */
@Entity
@Table(name = "tax_categories")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class TaxCategory extends BaseEntity {

    @Column(nullable = false, unique = true, length = 20, updatable = false)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    /** Percentage, e.g. 18.00. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal rate;

    @Column(name = "ebm_code", nullable = false, length = 1)
    private String ebmCode;

    @Column(length = 255)
    private String description;

    @Column(name = "is_default", nullable = false)
    private boolean defaultCategory;

    @Column(nullable = false)
    private boolean enabled = true;
}
