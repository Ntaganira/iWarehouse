package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : EbmItem.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A glass or processing service registered with the VSDC (/items/saveItems) before it is first sold:
 *               its EBM item code (fixed once made, it is on receipts) and the classification, tax letter and unit it
 *               was registered with; registered again when one of them changes. The simulator's registrations are
 *               kept apart from the VSDC's.
 * </pre>
 */
@Entity
@Table(name = "ebm_items")
@AuditedEntity(ref = "itemCode")
@Getter
@Setter
@NoArgsConstructor
public class EbmItem extends BaseEntity {

    @Column(name = "item_code", nullable = false, length = 20, updatable = false)
    private String itemCode;

    @Column(name = "product_id", updatable = false)
    private UUID productId;

    @Column(name = "service_id", updatable = false)
    private UUID serviceId;

    @Column(nullable = false, updatable = false)
    private boolean simulated;

    @Column(name = "item_class", nullable = false, length = 10)
    private String itemClass;

    @Column(name = "tax_code", nullable = false, length = 1)
    private String taxCode;

    @Column(name = "qty_unit", nullable = false, length = 5)
    private String qtyUnit;

    @Column(name = "registered_at", nullable = false)
    private LocalDateTime registeredAt;

    /** Registered as it is now: the same classification, tax letter and unit. */
    public boolean matches(String itemClass, String taxCode, String qtyUnit) {
        return this.itemClass.equals(itemClass) && this.taxCode.equals(taxCode) && this.qtyUnit.equals(qtyUnit);
    }
}
