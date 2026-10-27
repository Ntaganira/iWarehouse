package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.PerDiemCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : PerDiemRate.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The per diem of a travel category (CMP 08): in its currency, the meals and incidentals of a day and the
 *               accommodation of a night. A travel advance is worked out from it.
 * </pre>
 */
@Entity
@Table(name = "per_diem_rates")
@AuditedEntity(ref = "category")
@Getter
@Setter
@NoArgsConstructor
public class PerDiemRate extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 15, updatable = false)
    private PerDiemCategory category;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal meal = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal accommodation = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal incidentals = BigDecimal.ZERO;

    /** A day's meals and incidentals. */
    public BigDecimal getDaily() {
        return meal.add(incidentals);
    }
}
