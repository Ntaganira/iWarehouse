package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : StockTransferLine.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : One unit of a transfer and where it came from (INV-07).
 * </pre>
 */
@Entity
@Table(name = "stock_transfer_lines")
@AuditedEntity(ref = "unitCode")
@Getter
@Setter
@NoArgsConstructor
public class StockTransferLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false, updatable = false)
    private StockTransfer transfer;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "stock_unit_id", nullable = false, updatable = false)
    private UUID stockUnitId;

    @Column(name = "unit_code", nullable = false, length = 30, updatable = false)
    private String unitCode;

    @Column(name = "from_location_id", nullable = false, updatable = false)
    private UUID fromLocationId;
}
