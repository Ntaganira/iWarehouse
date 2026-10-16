package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.DeliveryNoteStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : DeliveryNote.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The delivery note and goods release authority of a paid invoice (F-04, DN-005, IMP 8 and 9): prepared
 *               by Dispatch with what leaves (the invoice's sheets and cut pieces), the vehicle and driver or the customer
 *               collecting, released by another person once the EBM receipt is signed (or with a logged override); the
 *               copy signed by the customer is recorded when it comes back, with their remarks. Lines in
 *               DeliveryNoteLine (append-only).
 * </pre>
 */
@Entity
@Table(name = "delivery_notes")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryNote extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private SalesInvoice invoice;

    /** The site the goods leave from. */
    @Column(name = "site_id")
    private UUID siteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DeliveryNoteStatus status = DeliveryNoteStatus.DRAFT;

    /** The customer collects at the counter: no vehicle. */
    @Column(nullable = false)
    private boolean collected;

    @Column(name = "vehicle_plate", length = 20)
    private String vehiclePlate;

    @Column(name = "driver_name", length = 100)
    private String driverName;

    @Column(length = 150)
    private String destination;

    /** The day it is planned to leave (G.23: against the warehouse's daily capacity). */
    @Column(name = "scheduled_on")
    private LocalDate scheduledOn;

    /** Why less was released than was ready (F-04: short delivery). */
    @Column(name = "short_reason", length = 255)
    private String shortReason;

    @Column(name = "prepared_by", nullable = false, length = 50, updatable = false)
    private String preparedBy;

    @Column(name = "prepared_by_id", updatable = false)
    private Long preparedById;

    @Column(name = "prepared_at", nullable = false, updatable = false)
    private LocalDateTime preparedAt;

    @Column(name = "released_by", length = 50)
    private String releasedBy;

    @Column(name = "released_by_id")
    private Long releasedById;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    /** The signed EBM receipt's number when the goods left. */
    @Column(name = "ebm_receipt", length = 40)
    private String ebmReceipt;

    /** Released before the EBM signature (the VSDC down), with the reason (decision D3). */
    @Column(name = "ebm_override_reason", length = 255)
    private String ebmOverrideReason;

    @Column(name = "ebm_override_by", length = 50)
    private String ebmOverrideBy;

    // --- The copy signed by the customer (IMP 9.2, 9.3)

    @Column(name = "copy_recorded_by", length = 50)
    private String copyRecordedBy;

    @Column(name = "copy_recorded_at")
    private LocalDateTime copyRecordedAt;

    @Column(name = "received_by_name", length = 100)
    private String receivedByName;

    @Column(name = "received_on")
    private LocalDate receivedOn;

    @Column(length = 500)
    private String remarks;

    /** The customer noted a shortage or damage. */
    @Column(name = "has_issue", nullable = false)
    private boolean issue;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "cancelled_by", length = 50)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    public boolean isDraft() {
        return status == DeliveryNoteStatus.DRAFT;
    }

    public boolean isReleased() {
        return status == DeliveryNoteStatus.RELEASED;
    }

    public boolean isCopyBack() {
        return copyRecordedAt != null;
    }
}
