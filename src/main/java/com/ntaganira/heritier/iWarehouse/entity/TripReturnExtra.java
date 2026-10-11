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
 * - File      : TripReturnExtra.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : A label scanned off a returning vehicle that is no unit expected back (FLT-10): a unit of another trip
 *               or place, a unit sold from the vehicle yet on board, or a label that is no unit's. Kept so the
 *               reconciliation puts it on the trip's audit case as unexpected; taken off if it was scanned by mistake.
 * </pre>
 */
@Entity
@Table(name = "trip_return_extras")
@AuditedEntity(ref = "code")
@Getter
@Setter
@NoArgsConstructor
public class TripReturnExtra extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, updatable = false)
    private Trip trip;

    @Column(nullable = false, length = 30, updatable = false)
    private String code;

    /** The unit the label is, when it is one. */
    @Column(name = "stock_unit_id", updatable = false)
    private UUID stockUnitId;

    @Column(name = "scanned_at", nullable = false, updatable = false)
    private LocalDateTime scannedAt;

    @Column(name = "scanned_by", length = 50, updatable = false)
    private String scannedBy;
}
