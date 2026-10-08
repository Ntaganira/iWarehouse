package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.enums.AllocationMethod;
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
 * - File      : ShipmentAllocation.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : What one posting of a shipment's costs gave one crate (PRC-04): the crate's basis (m²,
 *               value or kg), the RWF amount, and how it split between units in stock, units already
 *               gone (expensed) and sheets broken on arrival. A ledger: inserted by ShipmentService only,
 *               never changed (@Immutable here, trg_shipment_allocations_append_only in the database).
 * </pre>
 */
@Entity
@Immutable
@Table(name = "shipment_allocations")
@Getter
@Setter
@NoArgsConstructor
public class ShipmentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    @Column(name = "posting_no", nullable = false)
    private int postingNo;

    @Column(name = "crate_batch_id", nullable = false)
    private UUID crateBatchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AllocationMethod method;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal basis;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "stock_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal stockAmount;

    @Column(name = "expensed_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal expensedAmount;

    @Column(name = "broken_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal brokenAmount;

    @Column(name = "posted_at", nullable = false)
    private LocalDateTime postedAt;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;
}
