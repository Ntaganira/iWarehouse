package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Locale;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : ProductAlias.java
 * - Date      : 2026. 10. 31.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Another name or code a glass product is known by (TASK.md).
 *               Searches match the alias as typed, ignoring case.
 * </pre>
 */
@Entity
@Table(name = "product_aliases")
@AuditedEntity(ref = "alias")
@Getter
@Setter
@NoArgsConstructor
public class ProductAlias extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(nullable = false, length = 100)
    private String alias;

    @Column(name = "alias_normalized", nullable = false, length = 100)
    private String aliasNormalized;

    public ProductAlias(Product product, String alias) {
        this.product = product;
        setAlias(alias);
    }

    public void setAlias(String alias) {
        String cleaned = alias == null ? null : alias.trim();
        this.alias = cleaned == null || cleaned.isEmpty() ? null : cleaned;
        this.aliasNormalized = this.alias == null ? null : this.alias.toLowerCase(Locale.ROOT);
    }
}
