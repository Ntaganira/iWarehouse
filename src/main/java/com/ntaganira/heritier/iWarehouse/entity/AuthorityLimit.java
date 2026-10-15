package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityDecision;
import com.ntaganira.heritier.iWarehouse.enums.AuthorityLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : AuthorityLimit.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : One cell of the Delegation of Authority (Board Paper Annex E): whether an office may approve a decision
 *               and up to what amount (RWF, or % for a discount; no amount: no limit). Every decision has a row per office;
 *               the Board's always allows, without limit. Changed only on the Settings page, each change in its History.
 * </pre>
 */
@Entity
@Table(name = "authority_limits")
@AuditedEntity(ref = "decision")
@Getter
@Setter
@NoArgsConstructor
public class AuthorityLimit extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private AuthorityDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private AuthorityLevel level;

    @Column(nullable = false)
    private boolean allowed;

    @Column(name = "max_amount", precision = 18, scale = 2)
    private BigDecimal maxAmount;
}
