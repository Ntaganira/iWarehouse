package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : Currency.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : A currency documents may use (ACC-02), by ISO 4217 code. RWF is the base currency:
 *               ledgers are always in RWF and it can't be deactivated. Others are activated when the
 *               business starts buying in them. Deactivated, never deleted.
 * </pre>
 */
@Entity
@Table(name = "currencies")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class Currency extends BaseEntity {

    @Column(nullable = false, unique = true, length = 3, updatable = false)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 8)
    private String symbol;

    /** Minor units used when rounding amounts in this currency (RWF 0, USD 2). */
    @Column(nullable = false)
    private int decimals = 2;

    @Column(name = "is_base", nullable = false, updatable = false)
    private boolean baseCurrency;

    @Column(nullable = false)
    private boolean enabled = true;
}
