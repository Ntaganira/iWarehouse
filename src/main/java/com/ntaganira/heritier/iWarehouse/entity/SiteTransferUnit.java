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
 * - File      : SiteTransferUnit.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : A unit sent on a transfer between sites: the rack it left from and when, and (once its label is scanned at
 *               the other site) when it arrived, who received it and the rack it was put on.
 * </pre>
 */
@Entity
@Table(name = "site_transfer_units")
@AuditedEntity(ref = "unitCode")
@Getter
@Setter
@NoArgsConstructor
public class SiteTransferUnit extends BaseEntity {

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Column(name = "stock_unit_id", nullable = false, updatable = false)
    private UUID stockUnitId;

    @Column(name = "unit_code", nullable = false, length = 30, updatable = false)
    private String unitCode;

    @Column(name = "from_location_id", nullable = false, updatable = false)
    private UUID fromLocationId;

    @Column(name = "dispatched_at", nullable = false, updatable = false)
    private LocalDateTime dispatchedAt;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "received_by", length = 50)
    private String receivedBy;

    @Column(name = "to_location_id")
    private UUID toLocationId;

    public boolean isReceived() {
        return receivedAt != null;
    }
}
