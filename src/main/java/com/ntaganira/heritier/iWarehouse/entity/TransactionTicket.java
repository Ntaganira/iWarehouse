package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.TicketKind;
import com.ntaganira.heritier.iWarehouse.enums.TicketStatus;
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
 * - File      : TransactionTicket.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The Transaction Ticket of a stock movement (BP 7.4, F-05, Annex I): the document it covers (the supporting
 *               document, with its own documents), how many units, m² and their cost; raised with the movement, verified
 *               by Supply Chain against the glass, countersigned by Commercial (the movement answers to an order, a
 *               request or a return), posted by Finance. A goods receipt waiting for its ticket posts when Finance posts it.
 * </pre>
 */
@Entity
@Table(name = "transaction_tickets")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class TransactionTicket extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TicketKind kind;

    /** The movement's document: GOODS_RECEIPT, TRANSFER, ADJUSTMENT, TRIP, AUDIT_CASE, CREDIT_NOTE or SITE_TRANSFER. */
    @Column(name = "source_type", nullable = false, length = 30, updatable = false)
    private String sourceType;

    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @Column(name = "source_number", nullable = false, length = 40, updatable = false)
    private String sourceNumber;

    @Column(nullable = false, length = 255, updatable = false)
    private String summary;

    @Column(nullable = false, updatable = false)
    private int pieces;

    @Column(name = "area_m2", nullable = false, precision = 12, scale = 4, updatable = false)
    private BigDecimal areaM2;

    /** Their cost; a receipt waiting for its ticket gets it when it posts. */
    @Column(precision = 18, scale = 2)
    private BigDecimal value;

    /** Raised after the movement (a review), or before it (a goods receipt that posts from its ticket). */
    @Column(name = "movement_posted", nullable = false)
    private boolean movementPosted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private TicketStatus status;

    @Column(name = "raised_by", nullable = false, length = 50, updatable = false)
    private String raisedBy;

    @Column(name = "raised_by_id", updatable = false)
    private Long raisedById;

    @Column(name = "raised_at", nullable = false, updatable = false)
    private LocalDateTime raisedAt;

    @Column(name = "verified_by", length = 50)
    private String verifiedBy;

    @Column(name = "verified_by_id")
    private Long verifiedById;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verify_note", length = 255)
    private String verifyNote;

    @Column(name = "countersigned_by", length = 50)
    private String countersignedBy;

    @Column(name = "countersigned_by_id")
    private Long countersignedById;

    @Column(name = "countersigned_at")
    private LocalDateTime countersignedAt;

    @Column(name = "countersign_note", length = 255)
    private String countersignNote;

    @Column(name = "posted_by", length = 50)
    private String postedBy;

    @Column(name = "posted_by_id")
    private Long postedById;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "rejected_by", length = 50)
    private String rejectedBy;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    public boolean isOpen() {
        return status.isOpen();
    }

    /** The page of the document it covers. */
    /** The page of the document that moved the glass, or null when it has none. */
    public String getSourcePath() {
        String base = switch (sourceType) {
            case "GOODS_RECEIPT" -> "/goods-receipts/";
            case "TRANSFER" -> "/stock-transfers/";
            case "ADJUSTMENT" -> "/stock-adjustments/";
            case "TRIP" -> "/trips/";
            case "AUDIT_CASE" -> "/audit-cases/";
            case "CREDIT_NOTE" -> "/credit-notes/";
            case "SITE_TRANSFER" -> "/site-transfers/";
            default -> null;
        };
        return base == null ? null : base + sourceId;
    }
}
