package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.BreakageStatus;
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
 * - File      : TripBreakage.java
 * - Date      : 2026. 10. 11.
 * - User      : Hntaganira
 * - Desc      : A unit broken on the road (FLT-08, MPOS-06): reported by the driver on the phone (offline too, with the
 *               phone's own id so a report sent twice is kept once) or at the warehouse, with a photo (one of the trip's
 *               documents), the reason and the place. The supervisor accepts it (the unit is broken: spoilage when the trip
 *               is reconciled) or rejects it with a note (the unit is expected back), never the person who reported it.
 * </pre>
 */
@Entity
@Table(name = "trip_breakages")
@AuditedEntity(ref = "unitCode")
@Getter
@Setter
@NoArgsConstructor
public class TripBreakage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, updatable = false)
    private Trip trip;

    @Column(name = "stock_unit_id", nullable = false, updatable = false)
    private UUID stockUnitId;

    @Column(name = "unit_code", nullable = false, length = 30, updatable = false)
    private String unitCode;

    /** The phone's own id for the report (idempotent sync); none for a report made at the warehouse. */
    @Column(name = "client_id", unique = true, updatable = false)
    private UUID clientId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", updatable = false)
    private ApiDevice device;

    @Column(nullable = false, length = 255, updatable = false)
    private String reason;

    /** Where it broke: what the driver typed, with the phone's position when it gave one. */
    @Column(nullable = false, length = 255, updatable = false)
    private String place;

    @Column(name = "happened_at", nullable = false, updatable = false)
    private LocalDateTime happenedAt;

    @Column(name = "reported_by", nullable = false, length = 50, updatable = false)
    private String reportedBy;

    /** The photo, one of the trip's documents (AttachmentOwner.TRIP). */
    @Column(name = "photo_id", updatable = false)
    private UUID photoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BreakageStatus status = BreakageStatus.REPORTED;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decision_note", length = 255)
    private String decisionNote;

    public boolean isReported() {
        return status == BreakageStatus.REPORTED;
    }

    public boolean isAccepted() {
        return status == BreakageStatus.ACCEPTED;
    }
}
