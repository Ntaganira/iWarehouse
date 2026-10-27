package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.ntaganira.heritier.iWarehouse.enums.SalesTargetScope;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : SalesTarget.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A month's sales target (SDP 15, Annex D; T.07, T.09): for the business, a territory (its code), a salesperson (their
 *               username) or a market (DOMESTIC, EXPORT), net of VAT as the sales report reads sales.
 * </pre>
 */
@Entity
@Table(name = "sales_targets")
@AuditedEntity(ref = "targetKey")
@Getter
@Setter
@NoArgsConstructor
public class SalesTarget extends BaseEntity {

    @Column(name = "target_year", nullable = false, updatable = false)
    private int targetYear;

    @Column(name = "target_month", nullable = false, updatable = false)
    private int targetMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12, updatable = false)
    private SalesTargetScope scope;

    @Column(name = "target_key", nullable = false, length = 60, updatable = false)
    private String targetKey;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;
}
