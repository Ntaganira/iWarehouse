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
 * - File      : StockCountScan.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : A label scanned during a stock count and the place it was found (INV-08). One per code;
 *               scanning it again elsewhere moves the scan. Removable while the count is open.
 * </pre>
 */
@Entity
@Table(name = "stock_count_scans")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class StockCountScan extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "count_id", nullable = false, updatable = false)
    private StockCount count;

    @Column(nullable = false, length = 30, updatable = false)
    private String code;

    /** None when no unit has the code. */
    @Column(name = "stock_unit_id", updatable = false)
    private UUID stockUnitId;

    /** Where it was found. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "scanned_at", nullable = false)
    private LocalDateTime scannedAt;

    @Column(name = "scanned_by", length = 50)
    private String scannedBy;
}
