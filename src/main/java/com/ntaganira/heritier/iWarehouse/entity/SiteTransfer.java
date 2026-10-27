package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.SiteTransferStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : SiteTransfer.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Glass sent from one site to another (IMP 11, TRF-007, TCN-008, PF-04): asked by the receiving site with the
 *               reason and the expected dispatch day, approved by another person, dispatched with its vehicle, driver and
 *               expected arrival (the units go in transit), received label by label at the other site; labels that did
 *               not arrive are a difference, reported. Its lines (what was asked) are fixed once asked.
 * </pre>
 */
@Entity
@Table(name = "site_transfers")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class SiteTransfer extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_site_id", nullable = false, updatable = false)
    private Location fromSite;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_site_id", nullable = false, updatable = false)
    private Location toSite;

    @Column(nullable = false, length = 255, updatable = false)
    private String reason;

    @Column(name = "expected_dispatch", nullable = false, updatable = false)
    private LocalDate expectedDispatch;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SiteTransferStatus status;

    @Column(name = "requested_by", nullable = false, length = 50, updatable = false)
    private String requestedBy;

    @Column(name = "requested_by_id", updatable = false)
    private Long requestedById;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_by_id")
    private Long decidedById;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 255)
    private String decisionNote;

    @Column(name = "vehicle_plate", length = 20)
    private String vehiclePlate;

    @Column(name = "driver_name", length = 100)
    private String driverName;

    @Column(name = "expected_arrival")
    private LocalDateTime expectedArrival;

    @Column(name = "dispatched_by", length = 50)
    private String dispatchedBy;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "received_by", length = 50)
    private String receivedBy;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    /** What did not arrive, and what was done about it. */
    @Column(name = "difference_note", length = 500)
    private String differenceNote;

    public boolean isOpen() {
        return status.isOpen();
    }
}
