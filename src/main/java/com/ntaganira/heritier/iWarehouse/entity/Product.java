package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.GlassType;
import com.ntaganira.heritier.iWarehouse.service.GlassProducts;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Product.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : A glass product (MD-01): glass type + optional colour/finish + thickness, stocked and
 *               sold by the m². Type, colour/finish and thickness say what the glass is, so they are
 *               fixed after creation; a wrong product is deactivated and a new one added. Deactivated,
 *               never deleted.
 * </pre>
 */
@Entity
@Table(name = "products")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class Product extends BaseEntity {

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "glass_type", nullable = false, length = 20, updatable = false)
    private GlassType glassType;

    /** Colour or finish (Bronze, Grey, Low-iron); null for plain glass of the type. */
    @Column(length = 30, updatable = false)
    private String variant;

    @Column(name = "thickness_mm", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal thicknessMm;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tax_category_id", nullable = false)
    private TaxCategory taxCategory;

    /** INV-10: alert when stock falls below this many m²; null = no alert. */
    @Column(name = "reorder_level_m2", precision = 10, scale = 4)
    private BigDecimal reorderLevelM2;

    @Column(length = 255)
    private String notes;

    /** PRC-05: moving average cost per m² in RWF, set by posted receipts; null until the first one. */
    @Column(name = "mac_per_m2", precision = 18, scale = 4)
    private BigDecimal macPerM2;

    @Column(nullable = false)
    private boolean enabled = true;

    /** "6", "6.38". */
    public String getThicknessLabel() {
        return GlassProducts.thicknessLabel(thicknessMm);
    }
}
