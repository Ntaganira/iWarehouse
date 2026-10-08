package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.CostEntryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : StockCostEntry.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : One change of a stock unit's cost: the receipt cost, then each landed cost a shipment
 *               posting added (PRC-05), with the cost after it and the document that caused it. A ledger
 *               written by StockService only, never changed (@Immutable here,
 *               trg_stock_cost_entries_append_only in the database).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "stock_cost_entries")
@Getter
@Setter
@NoArgsConstructor
public class StockCostEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "stock_unit_id", nullable = false)
    private UUID stockUnitId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 12)
    private CostEntryType type;

    /** RWF added; the full cost for RECEIPT. */
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "cost_after", nullable = false, precision = 18, scale = 2)
    private BigDecimal costAfter;

    /** GOODS_RECEIPT or SHIPMENT. */
    @Column(name = "ref_type", length = 30)
    private String refType;

    @Column(name = "ref_id")
    private UUID refId;

    @Column(name = "ref_number", length = 30)
    private String refNumber;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;
}
