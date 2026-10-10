package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.SyncConflictReason;
import com.ntaganira.heritier.iWarehouse.enums.SyncConflictStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : SyncConflict.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A mobile sale the server could not take as it was made (SYNC-05): why, the sale as the phone sent it, who
 *               and when. Kept for the supervisor, never dropped: they record what was done (a counter sale, a credit note,
 *               an adjustment or nothing) and it is REVIEWED. The phone sent it once: its client UUID is unique, so a
 *               second sending finds it.
 * </pre>
 */
@Entity
@Table(name = "mobile_sync_conflicts")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class SyncConflict extends BaseEntity {

    @Column(name = "client_id", nullable = false, unique = true, updatable = false)
    private UUID clientId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", updatable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false, updatable = false)
    private ApiDevice device;

    @Column(nullable = false, length = 50, updatable = false)
    private String username;

    /** The invoice number the phone printed on its receipt. */
    @Column(length = 30, updatable = false)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private SyncConflictReason reason;

    /** What exactly: the units, prices or amounts in question. */
    @Column(length = 500, updatable = false)
    private String detail;

    /** What the phone charged. */
    @Column(name = "total_amount", precision = 18, scale = 2, updatable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payload;

    @Column(name = "client_created_at", updatable = false)
    private LocalDateTime clientCreatedAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncConflictStatus status = SyncConflictStatus.OPEN;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by", length = 50)
    private String reviewedBy;

    @Column(name = "review_note", length = 255)
    private String reviewNote;

    public boolean isOpen() {
        return status == SyncConflictStatus.OPEN;
    }
}
