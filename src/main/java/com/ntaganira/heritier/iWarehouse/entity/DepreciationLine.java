package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : DepreciationLine.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : An asset's depreciation in a month's run, and its depreciation to date after it (append-only, trigger).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "depreciation_lines")
@AuditedEntity(ref = "amount")
@Getter
@Setter
@NoArgsConstructor
public class DepreciationLine extends BaseEntity {

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false, updatable = false)
    private FixedAsset asset;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "accumulated_after", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal accumulatedAfter;
}
